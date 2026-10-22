import java.nio.file.Path;

/**
 * Runs the ReAct agent against the local Ollama model.
 *
 * Usage (from the project root, so books.json resolves):
 *   java [-Dsteps=N] [-Dtokens=N] [-Dprompt=path] -cp <classpath> RunAgent ["question"]
 */
public class RunAgent {

    private static final String QUESTION_A =
            "How many books did Ursula Le Guin write in the 1970s?";

    public static void main(String[] args) throws Exception {
        String query = args.length > 0 ? String.join(" ", args) : QUESTION_A;
        int steps = Integer.getInteger("steps", 3);
        int tokens = Integer.getInteger("tokens", 2000);
        Path prompt = Path.of(System.getProperty("prompt", "prompts/react.txt"));
        AgentDriver agent = new AgentDriver(new OllamaLlm(), prompt, steps, tokens);
        System.out.println(agent.run(query));
        System.err.println("tokens used (estimated): " + agent.getTokensUsed());
    }
}
