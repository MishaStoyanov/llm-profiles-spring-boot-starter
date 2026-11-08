# Quickstart

One profile, `assistant`, asked one question as a call and as a stream. The
Spring profile decides which vendor answers. Each call is logged with its
usage and cost.

Build the library first, then run the sample from this directory:

```sh
mvn -f ../.. install -DskipTests
mvn spring-boot:run                                         # Ollama on localhost:11434
mvn spring-boot:run -Dspring-boot.run.profiles=anthropic    # needs ANTHROPIC_API_KEY
mvn spring-boot:run -Dspring-boot.run.profiles=gemini       # needs GEMINI_API_KEY
```

A question of your own goes after `-Dspring-boot.run.arguments="..."`.

| Spring profile | vendor type         | environment                                   |
|----------------|---------------------|-----------------------------------------------|
| `ollama`       | `ollama`            | `OLLAMA_BASE_URL`, `OLLAMA_MODEL` (optional)  |
| `anthropic`    | `anthropic`         | `ANTHROPIC_API_KEY`, `ANTHROPIC_MODEL` (opt.) |
| `gemini`       | `openai-compatible` | `GEMINI_API_KEY`, `GEMINI_MODEL` (optional)   |
