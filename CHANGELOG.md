# Changelog

Notable changes, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/); before 1.0 a minor version may
break the API.

## [Unreleased]

Nothing has been released yet. What the first release will contain:

### Profiles and clients
- Vendors and profiles declared under `llm.*`, validated together at startup;
  every problem is reported in one message.
- `LlmClient` injected by field or parameter name (`localDraft` finds
  `local-draft`), or named with `@LlmProfile`; every injection point is
  checked before any bean is created, lazy ones included.
- A client is a handle on its profile and reads its settings on every call.
- Per-call overrides (`withModel`, `withTemperature`, `withMaxTokens`) that
  leave the profile as configured; `null` removes an override.
- Streaming with `stream(prompt)`: lazy, one listener event per stream with
  its usage and time to first token; cancellation is reported as such.
  The event is published before the end of the stream reaches the
  subscriber, so a stream seen to end has been accounted for.
- `asChatModel()`: a profile as a Spring AI `ChatModel` for `ChatClient`,
  advisors and tool calling, with every call still accounted for.
- `reasoning: off | low | medium | high | max` in a profile, and
  `withReasoning`: how much a model thinks, turned into each vendor's own
  setting; `{{reasoning}}` for webhooks, a `reasoning` column for the JDBC
  source.
- `LlmOptionsCustomizer` beans: the last word on the Spring AI options of
  every call, for what a profile cannot say.

### Runtime configuration
- `LlmConfigSource`: vendors, profiles and prices from sources besides YAML,
  each entry replacing the one of the same name whole.
- `llm.source.jdbc`: a source reading `llm_vendor`, `llm_profile` and
  `llm_price`; keys are stored as placeholders, never in full.
- `LlmProfiles.refresh()` and `llm.source.refresh-interval`: the new
  configuration is checked as at startup and takes effect at once, or not at
  all; unchanged vendors keep their connection.
- Profiles named in code cannot be removed by a refresh.
- `LlmProfileName`: profile names as enum constants, checked at startup.

### Vendors
- `anthropic`, `openai-compatible`, `ollama` over Spring AI 2.0, and
  `webhook` for workflows such as n8n, with a JSON request template and
  response paths.
- `timeout` for every vendor type: the whole call, or each piece of a stream.
- `max-retries`; a webhook is never retried.
- `openai-compatible` streams send only what every compatible server accepts:
  Gemini refused the `include_obfuscation` stream option. Checked against
  Gemini and Ollama for real.
- `webhook` speaks HTTP/1.1: over plain http the JDK client asked to upgrade
  to HTTP/2, and n8n left such requests unanswered until they timed out.
- `samples/n8n`: three n8n workflows on Ollama, started with Docker Compose,
  used as the profiles `triage`, `summarize` and `ask-data` (an AI Agent with
  a tool, whose usage is unknown).

### Accounting
- Every call becomes an `LlmCallEvent`: profile, vendor, model, start time,
  latency, usage, price at the time, tags and error.
- Usage the provider did not report is `null`, never zero.
- Prices per vendor and model in `llm.prices`; unpriced models are reported
  as unpriced, not free. Input and output cost are separate, and their sum is
  given only when both are known.
- Tags from the profile and from the caller (`withTag`).
- Built-in sinks: a log line per call, Micrometer meters (`llm.calls`,
  `llm.tokens`, `llm.cost`) and a JDBC row per call with the rates of the
  time; the table definition ships with the library.
- `llm.accounting.async`: listeners on a thread of their own, with a bounded
  queue that never drops an event.

[Unreleased]: https://github.com/MishaStoyanov/llm-profiles-spring-boot-starter/commits/master
