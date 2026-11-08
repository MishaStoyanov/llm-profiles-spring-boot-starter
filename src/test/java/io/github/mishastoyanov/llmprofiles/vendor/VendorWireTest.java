package io.github.mishastoyanov.llmprofiles.vendor;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import io.github.mishastoyanov.llmprofiles.LlmCallEvent;
import io.github.mishastoyanov.llmprofiles.LlmCallListener;
import io.github.mishastoyanov.llmprofiles.LlmClient;
import io.github.mishastoyanov.llmprofiles.LlmProfiles;
import io.github.mishastoyanov.llmprofiles.LlmResponse;
import io.github.mishastoyanov.llmprofiles.LlmUsage;
import io.github.mishastoyanov.llmprofiles.autoconfigure.LlmProfilesAutoConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Each built-in vendor against a local stand-in for its API: what the profile says has to reach
 * the wire under the vendor's own names, and what the vendor answers has to come back as usage,
 * model and text. Nothing here needs a key or a network.
 */
class VendorWireTest {

    private StubServer server;
    private final List<LlmCallEvent> events = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = new StubServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private int maxRetries = 0;

    private void withProfile(String type, String baseUrl, String timeout, Consumer<LlmClient> test) {
        List<String> properties = new ArrayList<>(List.of(
                "llm.vendors.v.type=" + type,
                "llm.vendors.v.base-url=" + baseUrl,
                "llm.vendors.v.api-key=test-key",
                "llm.profiles.p.vendor=v",
                "llm.profiles.p.model=m1",
                "llm.profiles.p.temperature=0.3",
                "llm.profiles.p.max-tokens=300",
                // Every vendor retries by default; here a retry would only hide what one call did.
                "llm.vendors.v.max-retries=" + maxRetries));
        if (timeout != null) {
            properties.add("llm.vendors.v.timeout=" + timeout);
        }
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LlmProfilesAutoConfiguration.class))
                .withBean(LlmCallListener.class, () -> events::add)
                .withPropertyValues(properties.toArray(String[]::new))
                .run(context -> test.accept(context.getBean(LlmProfiles.class).profile("p")));
    }

    /** Well before the stub would have answered, which takes 3 seconds. */
    private static void assertFailsFast(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        long start = System.nanoTime();
        assertThatThrownBy(call).hasStackTraceContaining("imeout");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    private static void assertNumber(JsonNode node, double expected) {
        assertThat(node.isNumber()).as(node + " is a number").isTrue();
        assertThat(node.doubleValue()).isEqualTo(expected);
    }

    @Nested
    class Anthropic {

        private static final String MESSAGE = """
                {"id": "msg_1", "type": "message", "role": "assistant", "model": "m1-20260101",
                 "content": [{"type": "text", "text": "Hello"}],
                 "stop_reason": "end_turn", "stop_sequence": null,
                 "usage": {"input_tokens": 11, "output_tokens": 4}}""";

        @Test
        void aCallCarriesTheProfileAndReadsTheUsage() {
            server.json(MESSAGE);
            withProfile("anthropic", server.url(), null, client -> {
                LlmResponse response = client.call("hi");

                assertThat(server.last().path()).isEqualTo("/v1/messages");
                JsonNode sent = server.last().json();
                assertThat(sent.get("model").asString()).isEqualTo("m1");
                assertThat(sent.get("max_tokens").asInt()).isEqualTo(300);
                assertNumber(sent.get("temperature"), 0.3);

                assertThat(response.text()).isEqualTo("Hello");
                assertThat(response.model()).isEqualTo("m1-20260101");
                assertThat(response.usage()).isEqualTo(new LlmUsage(11L, 4L, 15L));
            });
        }

        @Test
        void aStreamAddsUpUsageSentAtTheStartAndAtTheEnd() {
            server.events(
                    "message_start|{\"type\": \"message_start\", \"message\": {\"id\": \"msg_1\", \"type\": \"message\","
                            + " \"role\": \"assistant\", \"model\": \"m1-20260101\", \"content\": [], \"stop_reason\": null,"
                            + " \"stop_sequence\": null, \"usage\": {\"input_tokens\": 11, \"output_tokens\": 1}}}",
                    "content_block_start|{\"type\": \"content_block_start\", \"index\": 0,"
                            + " \"content_block\": {\"type\": \"text\", \"text\": \"\"}}",
                    "content_block_delta|{\"type\": \"content_block_delta\", \"index\": 0,"
                            + " \"delta\": {\"type\": \"text_delta\", \"text\": \"Hel\"}}",
                    "content_block_delta|{\"type\": \"content_block_delta\", \"index\": 0,"
                            + " \"delta\": {\"type\": \"text_delta\", \"text\": \"lo\"}}",
                    "content_block_stop|{\"type\": \"content_block_stop\", \"index\": 0}",
                    "message_delta|{\"type\": \"message_delta\", \"delta\": {\"stop_reason\": \"end_turn\","
                            + " \"stop_sequence\": null}, \"usage\": {\"output_tokens\": 4}}",
                    "message_stop|{\"type\": \"message_stop\"}");
            withProfile("anthropic", server.url(), null, client -> {
                assertThat(String.join("", client.stream("hi").collectList().block())).isEqualTo("Hello");

                JsonNode sent = server.last().json();
                assertThat(sent.get("stream").asBoolean()).isTrue();
                assertThat(sent.get("max_tokens").asInt()).isEqualTo(300);
                assertThat(events.getLast().failed()).isFalse();
                assertThat(events.getLast().usage()).isEqualTo(new LlmUsage(11L, 4L, 15L));
            });
        }

        @Test
        void aSlowAnswerHitsTheTimeout() {
            server.json(MESSAGE).delay(3_000);
            withProfile("anthropic", server.url(), "300ms", client -> assertFailsFast(() -> client.call("hi")));
        }
    }

    @Nested
    class OpenAiCompatible {

        private static final String COMPLETION = """
                {"id": "c1", "object": "chat.completion", "created": 1, "model": "m1-2026",
                 "choices": [{"index": 0, "message": {"role": "assistant", "content": "Hello"},
                              "finish_reason": "stop", "logprobs": null}],
                 "usage": {"prompt_tokens": 9, "completion_tokens": 2, "total_tokens": 11}}""";

        private static String chunk(String delta, String finish, String usage) {
            return "{\"id\": \"c1\", \"object\": \"chat.completion.chunk\", \"created\": 1, \"model\": \"m1-2026\","
                    + " \"choices\": " + (delta == null ? "[]" : "[{\"index\": 0, \"delta\": {\"content\": \"" + delta
                    + "\"}, \"finish_reason\": " + finish + "}]")
                    + (usage == null ? "" : ", \"usage\": " + usage) + "}";
        }

        @Test
        void aCallCarriesTheProfileAndReadsTheUsage() {
            server.json(COMPLETION);
            withProfile("openai-compatible", server.url() + "/v1", null, client -> {
                LlmResponse response = client.call("hi");

                assertThat(server.last().path()).isEqualTo("/v1/chat/completions");
                JsonNode sent = server.last().json();
                assertThat(sent.get("model").asString()).isEqualTo("m1");
                assertNumber(sent.get("temperature"), 0.3);
                JsonNode maxTokens = sent.has("max_completion_tokens") ? sent.get("max_completion_tokens") : sent.get("max_tokens");
                assertThat(maxTokens).as("max tokens under either of the protocol's names: " + sent).isNotNull();
                assertThat(maxTokens.asInt()).isEqualTo(300);
                assertThat(sent.has("stream_options")).as("only allowed on a stream").isFalse();

                assertThat(response.text()).isEqualTo("Hello");
                assertThat(response.model()).isEqualTo("m1-2026");
                assertThat(response.usage()).isEqualTo(new LlmUsage(9L, 2L, 11L));
            });
        }

        @Test
        void aStreamAsksForUsageAndGetsIt() {
            server.events(
                    chunk("Hel", "null", null),
                    chunk("lo", "\"stop\"", null),
                    chunk(null, null, "{\"prompt_tokens\": 9, \"completion_tokens\": 2, \"total_tokens\": 11}"),
                    "[DONE]");
            withProfile("openai-compatible", server.url() + "/v1", null, client -> {
                assertThat(String.join("", client.stream("hi").collectList().block())).isEqualTo("Hello");

                JsonNode sent = server.last().json();
                assertThat(sent.get("stream").asBoolean()).isTrue();
                assertThat(sent.at("/stream_options/include_usage").asBoolean()).as(sent.toString()).isTrue();
                assertThat(events.getLast().usage()).isEqualTo(new LlmUsage(9L, 2L, 11L));
                assertThat(events.getLast().model()).isEqualTo("m1-2026");
            });
        }

        @Test
        void aSlowAnswerHitsTheTimeout() {
            server.json(COMPLETION).delay(3_000);
            withProfile("openai-compatible", server.url() + "/v1", "300ms", client -> assertFailsFast(() -> client.call("hi")));
        }

        @Test
        void aChatClientOverTheProfileSpeaksTheProtocol() {
            server.json(COMPLETION);
            withProfile("openai-compatible", server.url() + "/v1", "5s", client -> {
                ChatClient chat = ChatClient.create(client.asChatModel());

                String text = chat.prompt().user("hi").options(ChatOptions.builder().temperature(0.9)).call().content();

                assertThat(text).isEqualTo("Hello");
                JsonNode sent = server.last().json();
                assertThat(sent.get("model").asString()).isEqualTo("m1");
                assertNumber(sent.get("temperature"), 0.9);
                assertThat(events.getLast().usage()).isEqualTo(new LlmUsage(9L, 2L, 11L));
            });
        }

        private static final String TOOL_CALL = """
                {"id": "c0", "object": "chat.completion", "created": 1, "model": "m1-2026",
                 "choices": [{"index": 0, "finish_reason": "tool_calls", "logprobs": null,
                              "message": {"role": "assistant", "content": null, "tool_calls": [
                                  {"id": "call_1", "type": "function",
                                   "function": {"name": "temperature", "arguments": "{\\"city\\": \\"Kyiv\\"}"}}]}}],
                 "usage": {"prompt_tokens": 20, "completion_tokens": 5, "total_tokens": 25}}""";

        static class Weather {
            final List<String> asked = new ArrayList<>();

            @Tool(description = "Current temperature in a city, in degrees Celsius")
            int temperature(String city) {
                asked.add(city);
                return 21;
            }
        }

        @Test
        void toolsGoThroughTheProfileAndEveryRoundIsAccountedFor() {
            server.jsonInTurn(TOOL_CALL, COMPLETION);
            Weather weather = new Weather();
            withProfile("openai-compatible", server.url() + "/v1", "5s", client -> {
                ChatClient chat = ChatClient.create(client.asChatModel());

                String text = chat.prompt().user("How warm is it in Kyiv?").tools(weather).call().content();

                assertThat(text).isEqualTo("Hello");
                assertThat(weather.asked).containsExactly("Kyiv");
                assertThat(server.requests).as("the question, then the tool's answer").hasSize(2);
                assertThat(server.requests.getFirst().json().at("/tools/0/function/name").asString())
                        .isEqualTo("temperature");
                assertThat(server.last().body()).contains("call_1");
                assertThat(events).as("one event per round trip to the vendor").hasSize(2);
                long tokens = events.stream().mapToLong(event -> event.usage().totalTokens()).sum();
                assertThat(tokens).as("both rounds, each counted once: " + events).isEqualTo(25 + 11);
            });
        }

        @Test
        void maxRetriesIsHonoured() {
            server.json("{\"error\": {\"message\": \"overloaded\", \"type\": \"server_error\"}}").status(500);
            maxRetries = 1;
            withProfile("openai-compatible", server.url() + "/v1", null, client -> {
                assertThatThrownBy(() -> client.call("hi"));
                assertThat(server.requests).as("the call and one retry").hasSize(2);
            });
        }
    }

    @Nested
    class Ollama {

        private static final String DONE = """
                {"model": "m1", "created_at": "2026-01-01T00:00:00Z",
                 "message": {"role": "assistant", "content": "Hello"},
                 "done": true, "done_reason": "stop", "total_duration": 100, "load_duration": 1,
                 "prompt_eval_count": 7, "prompt_eval_duration": 1, "eval_count": 3, "eval_duration": 1}""";

        @Test
        void aCallCarriesTheProfileAndReadsTheUsage() {
            server.json(DONE);
            withProfile("ollama", server.url(), null, client -> {
                LlmResponse response = client.call("hi");

                assertThat(server.last().path()).isEqualTo("/api/chat");
                JsonNode sent = server.last().json();
                assertThat(sent.get("model").asString()).isEqualTo("m1");
                assertNumber(sent.at("/options/temperature"), 0.3);
                assertThat(sent.at("/options/num_predict").asInt()).as(sent.toString()).isEqualTo(300);

                assertThat(response.text()).isEqualTo("Hello");
                assertThat(response.usage()).isEqualTo(new LlmUsage(7L, 3L, 10L));
            });
        }

        @Test
        void aStreamReadsTheUsageOnTheLastLine() {
            server.answer("application/x-ndjson", String.join("\n",
                    "{\"model\": \"m1\", \"created_at\": \"2026-01-01T00:00:00Z\", \"message\": {\"role\": \"assistant\", \"content\": \"Hel\"}, \"done\": false}",
                    "{\"model\": \"m1\", \"created_at\": \"2026-01-01T00:00:00Z\", \"message\": {\"role\": \"assistant\", \"content\": \"lo\"}, \"done\": false}",
                    DONE.replace("\"Hello\"", "\"\"").replace("\n", " ")) + "\n");
            withProfile("ollama", server.url(), "5s", client -> {
                assertThat(String.join("", client.stream("hi").collectList().block())).isEqualTo("Hello");

                assertThat(server.last().json().get("stream").asBoolean()).isTrue();
                assertThat(events.getLast().usage()).isEqualTo(new LlmUsage(7L, 3L, 10L));
            });
        }

        @Test
        void aSlowAnswerHitsTheTimeout() {
            server.json(DONE).delay(3_000);
            withProfile("ollama", server.url(), "300ms", client -> assertFailsFast(() -> client.call("hi")));
        }
    }
}
