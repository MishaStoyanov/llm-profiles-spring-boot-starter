# llm-profiles-spring-boot-starter

> **Status: 0.1 in progress.** Profiles, injection and the `anthropic`,
> `openai-compatible` and `ollama` vendors work; `webhook` and streaming do
> not yet. Sections marked *(target)* describe what the code is being written
> towards. The name is a working one.

Named LLM profiles for Spring Boot, declared in YAML and built on top of
Spring AI.

Several models of one vendor, several vendors, and n8n workflows are called
through one interface, and every call is accounted for honestly.

## Why

- Spring AI auto-configures one `ChatModel` per provider. A second model of
  the same vendor, or a second endpoint, means hand-written `@Bean`
  configuration.
- Tokens, latency and cost per call are left for the application to collect.
- A workflow behind a webhook (n8n) cannot be used as a model without custom
  code.

## Configuration (target)

```yaml
llm:
  vendors:
    anthropic:
      type: anthropic
      api-key: ${ANTHROPIC_API_KEY}
    ollama:
      type: ollama
      base-url: http://localhost:11434
    vllm-prod:
      type: openai-compatible
      base-url: https://llm.internal/v1
      api-key: ${VLLM_KEY}
    n8n:
      type: webhook
      base-url: https://n8n.example.com
      auth:
        header: X-Api-Key
        value: ${N8N_KEY}
      timeout: 120s

  profiles:
    summarizer:
      vendor: anthropic
      model: claude-haiku-4-5
      temperature: 0.2
    reviewer:
      vendor: anthropic
      model: claude-opus-5-5
      max-tokens: 4000
    local-draft:
      vendor: ollama
      model: llama3
    triage:
      vendor: n8n
      path: /webhook/triage
      request: '{"prompt": "{{prompt}}"}'
      response:
        text: $.output
        input-tokens: $.usage.input
        output-tokens: $.usage.output
```

`vendors` say where and how to connect. `profiles` say which model solves
which task and with what parameters.

## Usage

A field of type `LlmClient` gets the profile it is named after:

```java
@Service
@RequiredArgsConstructor
class ReportService {

    private final LlmClient summarizer;   // profile summarizer
    private final LlmClient localDraft;   // profile local-draft

    String summarise(String text) {
        return summarizer.chat("Summarise this: " + text);
    }
}
```

Case, dashes and underscores are ignored when names are matched, the way
Spring Boot matches YAML keys, so `localDraft` finds `local-draft`. When the
field should be called something else, name the profile:

```java
@LlmProfile("reviewer")
private final LlmClient client;
```

This works with Lombok's `@RequiredArgsConstructor` without any
`lombok.config`, and equally with a hand-written constructor or an `@Bean`
method parameter.

Every response says what the call took:

```java
LlmResponse response = summarizer
        .withModel("claude-sonnet-5-5")
        .withTemperature(0.1)
        .call("Review this diff: ...");

response.text();
response.usage().inputTokens();   // null when the provider did not report it
response.latency();
response.vendor();
response.model();                 // the model the provider says answered
```

Overrides return a new client and leave the profile as configured.

Where the profile is only known at runtime, the registry takes a name:

```java
llm.profile(request.profile()).chat(prompt);   // LlmProfiles llm
```

### Mistakes stop the application at startup

Before any bean is created, every `LlmClient` injection point is checked
against `llm.profiles`, and every mismatch is reported in one message,
including in lazy beans that would otherwise fail only when first used:

```
LlmClient injection does not match llm.profiles (declared: [summarizer, local-draft, reviewer]):
  - com.example.ReportService(constructor) parameter 'sumarizer': profile 'sumarizer' is not declared
```

The configuration itself is checked the same way: an unknown vendor type, a
profile pointing at an undeclared vendor, a missing model or API key.

Names are read from the compiled class, so the code has to be compiled with
`-parameters`. Projects built on `spring-boot-starter-parent` already are.

### Dependencies

Vendor modules are optional. Add the ones for the vendor types you use:

| type                | dependencies                                                   |
|---------------------|----------------------------------------------------------------|
| `anthropic`         | `spring-ai-anthropic`, `com.anthropic:anthropic-java-client-okhttp` |
| `openai-compatible` | `spring-ai-openai`, `com.openai:openai-java-client-okhttp`     |
| `ollama`            | `spring-ai-ollama`                                             |

A vendor type of your own is a bean implementing `LlmVendorAdapter`.

### Listening to calls

A bean implementing `LlmCallListener` sees every call, successful or not, with
its profile, vendor, model, latency and usage. A listener that throws is
logged and does not break the call.

## Principles

1. **A number that is not known is never shown as a number.** Missing usage
   is `null`, not zero. A model nobody has priced is named, not counted as
   free.
2. **Changing the model for a task is a config change**, not a code change.
3. **The library emits data and draws nothing.** Dashboards belong to Grafana
   or to the application.
4. **Thin.** Vendor clients come from Spring AI; this library does not
   reimplement them.

## Roadmap

**0.1: base**
- vendors and profiles from YAML
- vendor types: `anthropic`, `openai-compatible`, `ollama`, `webhook`
  (minimal: request template, response paths, synchronous only)
- programmatic overrides on top of a profile
- chat and streaming (no streaming for `webhook`)
- every response carries usage, latency, vendor and model
- a call listener hook, so that 0.2 adds accounting without changing the API

**0.2: accounting**
- one event per call and a sink interface
- sinks: log, Micrometer, JDBC
- prices in config, cost per call, price recorded as it was at the time of
  the call

**0.3: custom endpoints**
- richer webhook mapping, shaped by real cases rather than guessed in advance

**1.0** when the API has stopped moving.

## Non-goals

- a proxy or gateway server
- dashboards or any UI
- a general JSON mapping engine
- own abstractions for tool calling or multimodal input
- replacing Spring AI

## Origin

The core already exists inside
[SheetSmith](https://github.com/MishaStoyanov/sheetsmith): `LlmClientFactory`,
`TokenUsage`, `UsageRecorder`, `ModelPrice` and the price catalogue. This
project extracts that layer into a library. What has to change on the way out:

- the factory caches one active model → a registry keyed by profile
- settings live in the database → YAML as a source
- `UsageRecorder` depends on `User`, `JobRecord` and JPA → a sink interface
  with no domain types
- the provider `switch` on a string → vendor `type` and an adapter registry

## Decisions

- **Profiles are not Spring beans.** Clients are reached through the
  registry and injected by name, but never registered. As `ChatModel` beans
  they would make every unqualified `ChatModel` injection ambiguous and let
  calls bypass usage and latency reporting. As beans of any type, a single
  declared profile would be handed by Spring to a field of any name.
  Registering beans can still be added later without breaking anyone;
  removing it could not.

## Open questions

- final name
- template syntax for the webhook request body
