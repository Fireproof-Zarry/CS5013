# Session 6B Reflection

Model: `llama3.1:8b` via Ollama, temperature 0, ReAct prompt in `prompts/react.txt`.

## 1. Which tool did the agent prefer, and why?

The agent strongly preferred **`search_books`**. On question A ("How many books did
Ursula Le Guin write in the 1970s?") it called `search_books` first in every run, with
the argument (`Ursula K. Le Guin|1970|1979`). For the two-author comparison it
also started with `search_books`.

There are two reasons:

- The question already looks like a filter. It names an author and a time range,
  and the `search_books` description lists exactly those fields. Matching the words in
  the question to the words in the description is easy, even for a small model.
- The original prompt calls `list_books` "expensive" and says to use it "only if
  the query cannot be expressed as a filter". This made `search_books` the obvious
  first choice.

`list_books` only appeared as a fallback when the model stopped trusting
`search_books`:

- When a search returned a single book for a "how many" question, the model decided
  search "is not returning the correct results" and switched to `list_books`.
- In the comparison question, it tried to search both authors in one call. Only one
  author came back, so it gave up on search.

So the preference was right, but the model was too quick to doubt correct results.

## 2. How much did tool-description quality affect behaviour?

A lot, though not always in the way I expected.

| Change to descriptions | Tool choice | Outcome |
|---|---|---|
| Original (format + cost hint) | `search_books` (one `list_books` fallback) | Correct answer (1) in 5 iterations |
| Vague ("search for books with a filter", no argument format) | still `search_books` | Model invented `author="..." and year="1970-1979"`, which got `[]` 5 times. Hit the step budget with no answer |
| Format kept, "expensive" hint removed | `search_books` first, then `list_books` twice | Correct answer (1), but 5 iterations and ~5900 tokens |
| `list_books` described as "most reliable" | `search_books` first, then `list_books` | Prompt grew with the catalogue; Ollama timed out before an answer |

What I took from this:

- Tool choice was fairly robust. Vague wording did not stop the model from
  picking `search_books` first. The question itself mostly decided the first tool.
- Tool use was fragile. Removing the argument format broke every call. The
  description is the only place the model learns the calling convention, and without
  it the model makes one up. The tool's silent `[]` gave it nothing to fix.
- The cost hint mattered for fallbacks, not first choices. Without "expensive", the
  model reached for `list_books` more readily once it got confused. Each extra call
  re-sent the whole catalogue: the prompt grew from ~250 to ~2000 tokens, and in one run
  Ollama timed out.

Tool-side details mattered as much as the wording. An exact author match made "Ursula
Le Guin" (no "K.") return `[]`, and the model then made up an answer ("5 books").
Loosening the match fixed that without touching the prompt.

## 3. What would a production driver need on top of this?

- Structured tool calling with JSON schemas instead of regex-parsing `Action:` lines,
  so the model can't invent argument syntax or write fake `Observation:` lines.
- Argument validation and informative results. Return clear errors for bad input and
  counts or hints for empty results, instead of a silent `[]`.
- Real cost accounting from the provider's reported token usage, not a chars ÷ 4
  estimate.
- Loop detection and context management. Stop repeated identical calls, and trim large
  observations like the full catalogue instead of re-sending them every turn.
- Robust failure handling. Catch timeouts with retries, and return a clean "could not
  answer within budget" result when a budget fires.
- Logging, evaluation and guardrails. Keep per-step logs, a test set with known
  answers, and checks that the final answer matches the observations.

## Appendix: AgentDriver.java

```java
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A minimal ReAct-style agent loop.
 *
 * Each turn we send the LLM the running scratchpad ("Thought/Action/
 * Observation" transcript so far) plus the user's original query, parse
 * exactly one Action out of the LLM's response, call the corresponding
 * tool, append the Observation, and loop.
 *
 * Session 6B TODOs:
 *  - {@link #shouldStop(String, int)}: decide when we're done
 *    (either a "Final Answer:" line has appeared or we hit the step budget).
 *  - {@link #parseAction(String)}: extract the Action name and argument
 *    from the model's most recent response.
 */
public class AgentDriver {

    private static final Pattern FINAL_ANSWER =
            Pattern.compile("^\\s*Final Answer:", Pattern.MULTILINE);
    private static final Pattern ACTION =
            Pattern.compile("^\\s*Action:[ \\t]*(.*)$", Pattern.MULTILINE);
    private static final Pattern ACTION_INPUT =
            Pattern.compile("^\\s*Action Input:[ \\t]*(.*)$", Pattern.MULTILINE);

    private final LlmClient llm;
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Path promptTemplate;
    private final int stepBudget;
    private final int tokenBudget;
    private int tokensUsed;

    public AgentDriver(LlmClient llm, Path promptTemplate, int stepBudget) {
        this(llm, promptTemplate, stepBudget, Integer.MAX_VALUE);
    }

    /**
     * @param tokenBudget max estimated tokens (prompt + response, summed over
     *                    every LLM call) the run may spend
     */
    public AgentDriver(LlmClient llm, Path promptTemplate, int stepBudget, int tokenBudget) {
        this.llm = llm;
        this.promptTemplate = promptTemplate;
        this.stepBudget = stepBudget;
        this.tokenBudget = tokenBudget;
        registerTool(new ListBooksTool());
        registerTool(new SearchBooksTool());
    }

    public void registerTool(Tool t) {
        tools.put(t.name(), t);
    }

    public Map<String, Tool> getTools() {
        return tools;
    }

    /**
     * Run the loop. Returns the final scratchpad (transcript of all
     * thoughts, actions, observations, plus the last LLM response).
     */
    public String run(String userQuery) throws IOException {
        String template = Files.readString(promptTemplate);
        StringBuilder scratch = new StringBuilder();
        String lastResponse = "";
        tokensUsed = 0;
        for (int step = 0; step < stepBudget; step++) {
            String prompt = template
                    .replace("{query}", userQuery)
                    .replace("{scratchpad}", scratch.toString());
            // Check before calling: the prompt is the bulk of the cost, so
            // refuse a call we already know would blow the budget.
            int promptTokens = estimateTokens(prompt);
            if (tokensUsed + promptTokens > tokenBudget) {
                trace("=== iteration " + (step + 1) + " ===");
                trace("-> stop: token budget exceeded (used " + tokensUsed
                        + ", next prompt ~" + promptTokens + ", budget " + tokenBudget + ")");
                scratch.append("Observation: (token budget exceeded; stopping)\n");
                break;
            }
            lastResponse = llm.complete(prompt);
            int responseTokens = estimateTokens(lastResponse);
            tokensUsed += promptTokens + responseTokens;
            scratch.append(lastResponse).append('\n');
            trace("=== iteration " + (step + 1) + " ===");
            trace(lastResponse);
            trace("-> tokens: prompt ~" + promptTokens + " + response ~" + responseTokens
                    + " = total ~" + tokensUsed + " / " + tokenBudget);
            if (shouldStop(lastResponse, step)) {
                trace(FINAL_ANSWER.matcher(lastResponse).find()
                        ? "-> stop: final answer"
                        : "-> stop: step budget reached (" + stepBudget + ")");
                break;
            }
            Map.Entry<String, String> action = parseAction(lastResponse);
            if (action == null) {
                trace("-> no action parsed; stopping");
                scratch.append("Observation: (no action parsed; stopping)\n");
                break;
            }
            Tool t = tools.get(action.getKey());
            String obs = (t == null)
                    ? "ERROR: unknown tool " + action.getKey()
                    : t.call(action.getValue());
            trace("-> tool=" + action.getKey() + " arg=\"" + action.getValue() + "\"");
            trace("-> observation: " + obs);
            scratch.append("Observation: ").append(obs).append('\n');
        }
        return scratch.toString();
    }

    /** Estimated tokens spent by the last {@link #run}. */
    public int getTokensUsed() {
        return tokensUsed;
    }

    /**
     * Rough token count: ~4 characters per token for English text.  The
     * LlmClient interface only gives us strings, so we estimate rather than
     * ask the model's tokenizer.
     */
    static int estimateTokens(String s) {
        return s == null ? 0 : (s.length() + 3) / 4;
    }

    /** Trace log goes to stderr so it stays separate from the returned transcript. */
    private static void trace(String line) {
        System.err.println(line);
    }

    /**
     * True once we've hit the budget or the LLM has emitted a
     * "Final Answer:" line.
     */
    protected boolean shouldStop(String lastResponse, int step) {
        if (step >= stepBudget - 1) {
            return true;
        }
        return lastResponse != null && FINAL_ANSWER.matcher(lastResponse).find();
    }

    /**
     * Parse the first "Action: <name>" / "Action Input: <arg>" pair out of
     * {@code response} and return them as a (name, arg) entry, or null if
     * there is no Action line.  A missing Action Input becomes "".
     */
    protected Map.Entry<String, String> parseAction(String response) {
        if (response == null) {
            return null;
        }
        Matcher a = ACTION.matcher(response);
        if (!a.find()) {
            return null;
        }
        String name = a.group(1).trim();
        Matcher in = ACTION_INPUT.matcher(response);
        String arg = in.find(a.end()) ? stripQuotes(in.group(1).trim()) : "";
        return Map.entry(name, arg);
    }

    /** Models often wrap the argument in quotes or backticks; drop one matching pair. */
    private static String stripQuotes(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if (first == last && (first == '"' || first == '\'' || first == '`')) {
                return s.substring(1, s.length() - 1).trim();
            }
        }
        return s;
    }
}
```
