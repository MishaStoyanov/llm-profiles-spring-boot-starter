# llm-profiles-spring-boot-starter

[![CI](https://github.com/MishaStoyanov/llm-profiles-spring-boot-starter/actions/workflows/ci.yml/badge.svg)](https://github.com/MishaStoyanov/llm-profiles-spring-boot-starter/actions/workflows/ci.yml)

> **Status: 0.1 and 0.2 feature-complete, not yet released.** Profiles,
> injection, streaming, the Spring AI bridge, the `anthropic`,
> `openai-compatible`, `ollama` and `webhook` vendors, prices, the log,
> Micrometer and JDBC sinks, and profiles changed at runtime from a database
> work. The name is a working one.

Named LLM profiles for Spring Boot, declared in YAML and built on top of
Spring AI.

Several models of one vendor, several vendors, and n8n workflows are called
through one interface, and every call is accounted for honestly.

A runnable example is in [`samples/quickstart`](samples/quickstart); what
changed and when is in the [changelog](CHANGELOG.md).

## Why

- Spring AI auto-configures one `ChatModel` per provider. A second model of
  the same vendor, or a second endpoint, means hand-written `@Bean`
  configuration.
- Tokens, latency and cost per call are left for the application to collect.
- A workflow behind a webhook (n8n) cannot be used as a model without custom
  code.

## Configuration

```yaml
llm:
  vendors:
    anthropic:
      type: anthropic
      api-key: ${ANTHROPIC_API_KEY}
      timeout: 60s
      max-retries: 1
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
      request: '{"prompt": {{prompt}}}'
      response:
        text: $.output
        input-tokens: $.usage.input
        output-tokens: $.usage.output

  prices:                       # per vendor, then per model; per million tokens
    anthropic:
      claude-haiku-4-5:
        input-per-million: 1.00
        output-per-million: 5.00

  accounting:
    log:
      enabled: true             # one line per call
    jdbc:
      enabled: true             # one row per call, in the application's DataSource
```

`vendors` say where and how to connect. `profiles` say which model solves
which task and with what parameters. `prices` say what a model costs, and
`accounting` where the record of every call goes.

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

Overrides return a new client and leave the profile as configured. Passing
`null` removes an override, and the profile's own value applies again.

A client is a handle on its profile, not a copy: every call reads the
profile's settings as the registry has them at that moment.

### Streaming

```java
Flux<String> answer = summarizer.stream("Summarise this: " + text);
```

Nothing is sent until the `Flux` is subscribed to, and every subscription is
a call of its own. Listeners hear of a stream once, when it completes, fails
or is cancelled, with the usage the provider reported along the way and the
time to the first piece of text. A cancelled stream is reported as failed with
a `CancellationException`: it did not finish, and that is what accounting
needs to know.

`webhook` profiles do not stream; `stream()` on one throws
`UnsupportedOperationException` at once.

### Spring AI: `ChatClient`, advisors and the rest

```java
ChatClient chat = ChatClient.create(reviewer.asChatModel());

chat.prompt().user("Review this diff: ...").call().content();
```

`asChatModel()` hands the profile to anything in Spring AI that takes a
`ChatModel`. Calls through it are still calls through the profile: its
settings and the client's overrides apply, and listeners see them. Options
sent with a prompt win over the profile's model, temperature and max tokens,
as Spring AI expects of runtime options; everything else in them, tools
included, reaches the vendor as it is. The returned model is not a bean, for
the reason in Decisions.

With tools, `ChatClient` sends the question, runs the tool and sends its
result back: two round trips to the vendor, two calls through the profile,
and listeners hear of each with its own usage.

### Timeouts and retries

`timeout` bounds the wait for the vendor: for a call, the whole answer; for a
stream, each piece of it, the first included, so a long answer that keeps
coming is not cut off. Each retry waits as long again.

`max-retries` is how often a failed call is tried again. Both are optional and
default to what the vendor's client does on its own:

| type                | timeout                         | retries                    |
|---------------------|---------------------------------|----------------------------|
| `anthropic`         | SDK default (10 minutes)        | 2                          |
| `openai-compatible` | 60 s per call (Spring AI's)     | 2                          |
| `ollama`            | none                            | 10, with pauses up to 3 min |
| `webhook`           | 60 s                            | never                      |

Spring AI's Ollama retries a timed-out call ten times with growing pauses, so
without `max-retries` a timeout of a few seconds can still take minutes to
fail. A webhook is never retried: a workflow may have side effects, and
`max-retries` on one stops the application at startup.

Where the profile is only known at runtime, the registry takes a name:

```java
llm.profile(request.profile()).chat(prompt);   // LlmProfiles llm
```

Or a name the compiler knows, from an enum:

```java
enum Profiles implements LlmProfileName { SUMMARIZER, LOCAL_DRAFT }

llm.profile(Profiles.LOCAL_DRAFT).chat(prompt);   // profile local-draft
```

`LlmProfileName` asks only for `name()`, which every enum already has.

### Mistakes stop the application at startup

Before any bean is created, every `LlmClient` injection point and every
constant of an `LlmProfileName` enum in the application's packages is checked
against `llm.profiles`, and every mismatch is reported in one message,
including in lazy beans that would otherwise fail only when first used:

```
Profile names in code do not match llm.profiles (declared: [summarizer, local-draft, reviewer]):
  - com.example.ReportService(constructor) parameter 'sumarizer': profile 'sumarizer' is not declared
  - com.example.Profiles.REVEIWER: profile 'REVEIWER' is not declared
```

The configuration itself is checked the same way: an unknown vendor type, a
profile pointing at an undeclared vendor, a missing model or API key, a
timeout that is not positive.

Names are read from the compiled class, so the code has to be compiled with
`-parameters`. Projects built on `spring-boot-starter-parent` already are.

### Dependencies

Vendor modules are optional. Add the ones for the vendor types you use:

| type                | dependencies                                                   |
|---------------------|----------------------------------------------------------------|
| `anthropic`         | `spring-ai-anthropic`, `com.anthropic:anthropic-java-client-okhttp` |
| `openai-compatible` | `spring-ai-openai`, `com.openai:openai-java-client-okhttp`     |
| `ollama`            | `spring-ai-ollama`                                             |

`webhook` needs nothing extra. A vendor type of your own is a bean
implementing `LlmVendorAdapter`.

### Webhooks

A `webhook` profile POSTs a JSON body to `base-url` + `path` and reads the
answer back, so an n8n workflow is called exactly like a model.

```yaml
triage:
  vendor: n8n
  path: /webhook/triage
  request: '{"prompt": {{prompt}}, "source": "support"}'
  response:
    text: $.output
    input-tokens: $.usage.input
    output-tokens: $.usage.output
```

A placeholder is replaced by a complete JSON value: a string arrives already
quoted and escaped, a number as a number, an unset value as `null`. That is
why it is written **without quotes**. A prompt containing quotes or line
breaks then cannot break the body, which pasting raw text between quotes
would. Available placeholders: `{{prompt}}`, `{{profile}}`, `{{model}}`,
`{{temperature}}`, `{{max-tokens}}`.

Response paths take `.names` and `[indexes]`, for example
`$.choices[0].message.content`. `$` is the whole body, and then a plain-text
answer is accepted as well. Token paths are optional; without them usage is
unknown, not zero.

At startup an unknown placeholder, a template that is not valid JSON once
filled in, or a malformed path stops the application. A call that gets a
non-2xx status, or an answer without the text, fails with
`WebhookCallException` quoting the start of the body.

`auth` sends one header with every request. `timeout` defaults to 60 seconds.
Webhooks are synchronous only, and never retried.

## Changing profiles while the application runs

Profiles, vendors and prices can come from somewhere other than YAML, and
change without a restart: a different model for a task, a new key, a second
endpoint of a vendor type the application already has.

```yaml
llm:
  source:
    jdbc:
      enabled: true           # read llm_vendor, llm_profile and llm_price
    refresh-interval: 1m      # optional; otherwise call LlmProfiles.refresh()
```

`llm.profiles` is read first, then every source. A vendor, profile or price
from a source replaces the one of the same name before it **as a whole**: a
row that sets only the model has no temperature, whatever the YAML said. A
source can add profiles, but cannot remove one declared before it.

`refresh()` reads everything again and, if the result passes the same checks
as at startup, switches to it at once. Calls under way finish with what they
started with; every client, injected ones included, uses the new settings from
its next call. A vendor whose settings did not change keeps its connection.
When the new configuration is invalid, `refresh()` throws with every problem
and nothing changes; the periodic refresh logs it instead, once.

The application's own `llm.*` is bound afresh on each refresh too, so a
Spring Cloud Config refresh followed by `refresh()` takes effect.

**Names in code stay.** An injection point or an `LlmProfileName` constant is
checked against `llm.profiles` at startup, before a database can be read, so
the profile it names has to be declared there; a source may change its
settings, not remove it. A refresh that would remove one is refused. A profile
only a source knows is reached by name, `llm.profile("triage")`; once it is
removed, a call through it fails with `UnknownProfileException`.

What still needs a restart: a new vendor *type*, which needs its adapter on
the classpath, and anything under `llm.accounting` or `llm.source`.

### From a database

Tables `llm_vendor`, `llm_profile` and `llm_price`
(`llm.source.jdbc.table-prefix`, `llm_` by default, may carry a schema). The
definitions ship at `io/github/mishastoyanov/llmprofiles/source/llm_config.sql`
for PostgreSQL and H2: copy them into your migrations, or set
`llm.source.jdbc.create-tables: true` to try things out. A row is read the
way the same keys in YAML are: `timeout` is written `60s`, `tags` is a JSON
object. Needs `spring-jdbc` on the classpath.

```sql
INSERT INTO llm_vendor (name, type, api_key) VALUES ('anthropic', 'anthropic', '${ANTHROPIC_API_KEY}');
UPDATE llm_profile SET model = 'claude-sonnet-5-5' WHERE name = 'reviewer';
```

**No secrets in the table.** `api_key` and `auth_value` must be a
placeholder, resolved against the application's environment: an environment
variable, a system property, or whatever else the environment holds, Vault
and secret managers included. A key written out in full is refused, and so is
a placeholder that resolves to nothing.

### A source of your own

A bean implementing `LlmConfigSource` returns `LlmSettings`: vendors,
profiles and prices. It is read at startup and on every refresh, after YAML
and the built-in sources, in bean order.

## Accounting

Every call, successful or not, streamed or not, made directly or through
`asChatModel()`, becomes one `LlmCallEvent`: profile, vendor, model, when it
started, latency (and for a stream the time to its first piece of text),
usage, the price at the time, tags and the error if there was one.

### Prices

```yaml
llm:
  prices:
    anthropic:                  # a vendor name from llm.vendors
      claude-haiku-4-5:
        input-per-million: 1.00
        output-per-million: 5.00
```

The library ships no price list: one in the repository would be wrong within
months while still producing confident totals. It knows what it is told.

A price is looked up for the model that answered, then for the one asked for,
since providers answer with dated names (`claude-haiku-4-5-20251001`) for
undated ones. A model with no price is *unpriced*: `event.priced()` is false
and every cost is null, never zero.

Input and output are priced separately: `event.inputCost()` and
`event.outputCost()` are each known when the provider reported that kind of
token. `event.cost()` is their sum, and null when either is missing, as after
a stream cut off before its last piece: half a bill is not a smaller bill, it
is an unknown one, while the half that is known stays readable. Prices are per vendor, because the same model behind two
endpoints need not cost the same; the numbers are in whatever currency you
write them in.

### Tags

Who caused a call, and for which piece of work, is the application's to say:

```java
summarizer.withTag("user", userId).withTag("job", jobId).chat(prompt);
```

```yaml
llm:
  profiles:
    summarizer:
      tags:
        feature: summaries
```

A client's tags lie over its profile's. They reach every sink, except metrics:
values like user ids have no end of distinct values, which a metrics backend
cannot afford.

### Sinks

A sink is a bean implementing `LlmCallListener`; declare one and it is called
for every call, on the calling thread, after the call. A listener that throws
is logged and does not break the call.

With `llm.accounting.async.enabled: true` listeners are called on a thread of
their own instead, so a slow database does not slow calls. The queue holds
`llm.accounting.async.queue-capacity` events (10 000); when it is full, the
call that adds one more delivers it itself rather than lose it. Events still
waiting at shutdown are delivered, for up to ten seconds. Three come built in:

| sink       | switch                                  | default |
|------------|-----------------------------------------|---------|
| log        | `llm.accounting.log.enabled`            | off     |
| Micrometer | `llm.accounting.metrics.enabled`        | on, when the application has a `MeterRegistry` |
| JDBC       | `llm.accounting.jdbc.enabled`           | off     |

**Log.** One line per call on `io.github.mishastoyanov.llmprofiles.calls`,
INFO for a success and WARN for a failure. What is not known is written as `?`:

```
profile=summarizer vendor=anthropic model=claude-haiku-4-5-20251001 latency=812ms input=120 output=40 cost=0.00032 (input 0.00012, output 0.0002) tags={job=42}
profile=summarizer vendor=anthropic model=claude-haiku-4-5 latency=95ms input=120 output=? cost=? (input 0.00012, output ?) failed=CancellationException: ...
profile=local-draft vendor=ollama model=llama3 latency=2140ms input=? output=? cost=unpriced
```

**Micrometer.** `llm.calls` (timer; tags `profile`, `vendor`, `model`,
`outcome` = `success` / `error` / `cancelled`, `priced`), `llm.tokens`
(counter; plus `type` = `input` / `output`, only counts the provider
reported) and `llm.cost` (counter; plus `type` = `input` / `output`, each
only when the model has a price and that kind of token was reported).
Summing `llm.cost` gives what is known; `llm.calls{priced="false"}` shows
how much is not.

**JDBC.** One row per call in the application's `DataSource`, table
`llm_call` (`llm.accounting.jdbc.table` to rename, with a schema if needed).
The table definition ships at
`io/github/mishastoyanov/llmprofiles/accounting/llm_call.sql`, written for
PostgreSQL and H2: copy it into your migrations, or set
`llm.accounting.jdbc.create-table: true` to try things out. Needs
`spring-jdbc` on the classpath.

A row stores the rates per million tokens as they were at the time of the
call, not a total, so that correcting a price tomorrow does not move last
month's numbers, and a cheap call can be told from a mispriced one:

```sql
SELECT profile,
       SUM(input_tokens * input_per_million + output_tokens * output_per_million) / 1e6 AS cost,
       COUNT(*) FILTER (WHERE input_per_million IS NULL)                                AS unpriced_calls
FROM llm_call
GROUP BY profile;
```

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

**0.1: base** (done, unreleased)
- vendors and profiles from YAML
- vendor types: `anthropic`, `openai-compatible`, `ollama`, `webhook`
  (minimal: request template, response paths, synchronous only)
- programmatic overrides on top of a profile
- chat and streaming (no streaming for `webhook`)
- a profile as a Spring AI `ChatModel`, still accounted for
- timeouts and retries per vendor
- every response carries usage, latency, vendor and model
- a call listener hook, so that 0.2 adds accounting without changing the API

**0.2: accounting** (done, unreleased)
- one event per call and a sink interface
- sinks: log, Micrometer, JDBC
- prices in config, cost per call, price recorded as it was at the time of
  the call
- tags on calls, from the profile and from the caller

**Runtime configuration** (done, unreleased)
- vendors, profiles and prices from a database or a source of your own
- refresh on demand or on a schedule, all or nothing, without a restart
- profile names as enum constants, checked at startup
- listeners called off the calling thread, on request

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
- **Names in code are declared in YAML.** Injection points and enum
  constants are checked before any bean exists, when no database can be read
  yet, and a refresh may not remove what they name. Checking later would let
  a typo reach production; a profile only a database knows is still reachable
  by name.
- **A source replaces whole entries.** Merging field by field would leave no
  way for a database row to unset a temperature the YAML has, and would make
  what a profile does depend on two places at once.
- **Webhook placeholders are JSON values, not text.** `{{prompt}}`, not
  `"{{prompt}}"`. Unusual, but it is the difference between escaping being
  the library's job and being every user's. Open to revision once real
  workflows have used it.

## Open questions

- final name
