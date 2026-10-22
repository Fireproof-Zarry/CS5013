#!/usr/bin/env bash
# Re-runs every Session 6B experiment against the local Ollama model and
# saves each trace (stderr trace log + stdout transcript) under traces/.
# Usage: ./run-traces.sh            (all experiments)
#        ./run-traces.sh 4b-nocost  (just one, by trace name)
set -u
cd "$(dirname "$0")"
make build >/dev/null || exit 1
CP="libs/junit.jar:$(find libs/langchain4j -name '*.jar' | paste -sd:):build"
mkdir -p traces

QA="How many books did Ursula Le Guin write in the 1970s?"
QCMP="Compare the publishing decades of Octavia Butler and Neal Stephenson: in which decades did each of them publish books, and which decades do they share?"
QGENRE="Which genres appear in the catalogue?"
NOLIMIT=1000000

run() {  # name steps tokens prompt question
    local name=$1 steps=$2 tokens=$3 prompt=$4 question=$5
    if [ -n "${ONLY:-}" ] && [ "$ONLY" != "$name" ]; then return; fi
    local out="traces/$name.log"
    echo "running $name ..."
    {
        echo "# experiment: $name"
        echo "# date:       $(date -Iseconds)"
        echo "# model:      llama3.1:8b (Ollama, temperature 0)"
        echo "# prompt:     $prompt"
        echo "# steps:      $steps"
        echo "# tokens:     $([ "$tokens" = $NOLIMIT ] && echo 'no limit' || echo "$tokens")"
        echo "# question:   $question"
        echo
        echo "################ TRACE LOG (stderr) + TRANSCRIPT (stdout) ################"
        java -Dsteps="$steps" -Dtokens="$tokens" -Dprompt="$prompt" -cp "$CP" \
            RunAgent "$question" 2>&1 | grep -v '^SLF4J'
        local status=${PIPESTATUS[0]}
        echo
        echo "# exit status: $status"
    } > "$out"
}

ONLY=${1:-}
run 3-question-a        5 $NOLIMIT prompts/react.txt          "$QA"
run 4a-vague            5 $NOLIMIT prompts/react-vague.txt    "$QA"
run 4b-nocost           5 $NOLIMIT prompts/react-nocost.txt   "$QA"
run 4c-listbest         5 $NOLIMIT prompts/react-listbest.txt "$QA"
run 5-step-budget       3 $NOLIMIT prompts/react.txt          "$QCMP"
run 6a-tokens-question-a 5 2000    prompts/react.txt          "$QA"
run 6d-tokens-under     5 4000     prompts/react.txt          "$QA"
run 6b-tokens-fires     5 1200     prompts/react.txt          "$QCMP"
run 6c-tokens-listbooks 5 1000     prompts/react.txt          "$QGENRE"
echo "done; traces in traces/"
