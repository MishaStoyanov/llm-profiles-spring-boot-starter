package io.github.mishastoyanov.llmprofiles.vendor.webhook;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import io.github.mishastoyanov.llmprofiles.LlmCallEvent;
import io.github.mishastoyanov.llmprofiles.LlmCallListener;
import io.github.mishastoyanov.llmprofiles.LlmProfiles;
import io.github.mishastoyanov.llmprofiles.LlmProfilesConfigurationException;
import io.github.mishastoyanov.llmprofiles.LlmResponse;
import io.github.mishastoyanov.llmprofiles.LlmUsage;
import io.github.mishastoyanov.llmprofiles.autoconfigure.LlmProfilesAutoConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookVendorTest {

    private HttpServer server;
    private final List<String> bodies = new ArrayList<>();
    private final List<Headers> headers = new ArrayList<>();
    private int status = 200;
    private String answer = "{\"output\": \"done\", \"usage\": {\"input\": 7, \"output\": 2}}";

    private ApplicationContextRunner runner;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/webhook/", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            headers.add(exchange.getRequestHeaders());
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();

        runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LlmProfilesAutoConfiguration.class))
                .withPropertyValues(
                        "llm.vendors.n8n.type=webhook",
                        // A trailing slash here and a leading one on the path still make one slash.
                        "llm.vendors.n8n.base-url=http://localhost:" + server.getAddress().getPort() + "/",
                        "llm.vendors.n8n.auth.header=X-Api-Key",
                        "llm.vendors.n8n.auth.value=secret",
                        "llm.vendors.n8n.timeout=5s",
                        "llm.profiles.triage.vendor=n8n",
                        "llm.profiles.triage.path=/webhook/triage",
                        "llm.profiles.triage.request={\"prompt\": {{prompt}}, \"profile\": {{profile}}, \"temperature\": {{temperature}}}",
                        "llm.profiles.triage.response.text=$.output",
                        "llm.profiles.triage.response.input-tokens=$.usage.input",
                        "llm.profiles.triage.response.output-tokens=$.usage.output");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void aWebhookIsCalledLikeAModel() {
        runner.run(context -> {
            LlmResponse response = context.getBean(LlmProfiles.class).profile("triage").call("ticket #1");

            assertThat(response.text()).isEqualTo("done");
            assertThat(response.usage()).isEqualTo(new LlmUsage(7L, 2L, 9L));
            assertThat(response.vendor()).isEqualTo("n8n");
            assertThat(response.model()).as("a webhook profile needs no model").isNull();
            assertThat(headers.getFirst().getFirst("X-Api-Key")).isEqualTo("secret");
            assertThat(headers.getFirst().getFirst("Content-Type")).isEqualTo("application/json");
            assertThat(headers.getFirst().containsKey("Upgrade"))
                    .as("n8n never answers a request that asks to upgrade to HTTP/2").isFalse();
        });
    }

    @Test
    void aWebhookServesAsAChatModelToo() {
        runner.run(context -> {
            ChatResponse response = context.getBean(LlmProfiles.class).profile("triage").asChatModel()
                    .call(new Prompt("ticket #2"));

            assertThat(response.getResult().getOutput().getText()).isEqualTo("done");
            assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(9);
            assertThat(JsonMapper.shared().readTree(bodies.getFirst()).path("prompt").stringValue()).isEqualTo("ticket #2");
        });
    }

    @Test
    void retriesAreRefusedForAWebhook() {
        runner.withPropertyValues("llm.vendors.n8n.max-retries=2").run(context -> assertThat(context).getFailure()
                .hasMessageContaining("vendor 'n8n': max-retries does not apply to a webhook"));
    }

    @Test
    void aPromptWithQuotesAndLineBreaksArrivesIntact() {
        runner.run(context -> {
            String prompt = "say \"hi\"\nthen {{prompt}} \\ done";
            context.getBean(LlmProfiles.class).profile("triage").withTemperature(0.3).call(prompt);

            JsonNode sent = JsonMapper.shared().readTree(bodies.getFirst());
            assertThat(sent.path("prompt").stringValue()).isEqualTo(prompt);
            assertThat(sent.path("profile").stringValue()).isEqualTo("triage");
            assertThat(sent.path("temperature").doubleValue()).isEqualTo(0.3);
        });
    }

    @Test
    void aValueNobodySetIsSentAsNull() {
        runner.run(context -> {
            context.getBean(LlmProfiles.class).profile("triage").call("x");

            assertThat(JsonMapper.shared().readTree(bodies.getFirst()).path("temperature").isNull()).isTrue();
        });
    }

    @Test
    void usageTheWorkflowDoesNotReportIsUnknown() {
        answer = "{\"output\": \"done\"}";
        runner.run(context -> assertThat(context.getBean(LlmProfiles.class).profile("triage").call("x").usage())
                .isEqualTo(new LlmUsage(null, null, null)));
    }

    @Test
    void aPlainTextAnswerIsTakenWholeWhenTheTextPathIsTheBody() {
        answer = "just text, not JSON";
        runner.withPropertyValues("llm.profiles.triage.response.text=$").run(context ->
                assertThat(context.getBean(LlmProfiles.class).profile("triage").chat("x")).isEqualTo("just text, not JSON"));
    }

    @Test
    void nestedPathsReachIntoArrays() {
        answer = "{\"choices\": [{\"message\": {\"content\": \"deep\"}}]}";
        runner.withPropertyValues("llm.profiles.triage.response.text=$.choices[0].message.content").run(context ->
                assertThat(context.getBean(LlmProfiles.class).profile("triage").chat("x")).isEqualTo("deep"));
    }

    @Test
    void anErrorStatusFailsTheCallAndReachesListeners() {
        status = 500;
        answer = "{\"message\": \"workflow crashed\"}";
        List<LlmCallEvent> events = new ArrayList<>();
        runner.withBean(LlmCallListener.class, () -> events::add).run(context -> {
            assertThatThrownBy(() -> context.getBean(LlmProfiles.class).profile("triage").chat("x"))
                    .isInstanceOf(WebhookCallException.class)
                    .hasMessageContaining("answered 500")
                    .hasMessageContaining("workflow crashed");
            assertThat(events).singleElement().satisfies(event -> assertThat(event.failed()).isTrue());
        });
    }

    @Test
    void anAnswerWithoutTheTextFailsSayingWhereItLooked() {
        answer = "{\"result\": \"elsewhere\"}";
        runner.run(context -> assertThatThrownBy(() -> context.getBean(LlmProfiles.class).profile("triage").chat("x"))
                .isInstanceOf(WebhookCallException.class)
                .hasMessageContaining("answer has nothing at $.output")
                .hasMessageContaining("elsewhere"));
    }

    @Test
    void quotingAPlaceholderIsCaughtAtStartupWithAHint() {
        runner.withPropertyValues("llm.profiles.triage.request={\"prompt\": \"{{prompt}}\"}").run(context ->
                assertThat(context).getFailure().rootCause()
                        .isInstanceOf(LlmProfilesConfigurationException.class)
                        .hasMessageContaining("profile 'triage': request template is not valid JSON once filled in")
                        .hasMessageContaining("write {{prompt}}, not \"{{prompt}}\""));
    }

    @Test
    void everyWebhookProfileProblemIsReportedAtStartup() {
        runner.withPropertyValues(
                        "llm.profiles.typo.vendor=n8n",
                        "llm.profiles.typo.path=/webhook/typo",
                        "llm.profiles.typo.request={\"prompt\": {{promt}}}",
                        "llm.profiles.typo.response.text=$.output",
                        "llm.profiles.bare.vendor=n8n",
                        "llm.profiles.badpath.vendor=n8n",
                        "llm.profiles.badpath.path=/webhook/x",
                        "llm.profiles.badpath.request={\"prompt\": {{prompt}}}",
                        "llm.profiles.badpath.response.text=output")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("profile 'typo': request template uses unknown placeholders [promt]")
                        .hasMessageContaining("profile 'bare': path is required")
                        .hasMessageContaining("profile 'bare': request is required")
                        .hasMessageContaining("profile 'bare': response.text is required")
                        .hasMessageContaining("profile 'badpath': response path 'output' must start with $"));
    }

    @Test
    void authNeedsBothHalves() {
        runner.withPropertyValues("llm.vendors.n8n.auth.value=").run(context ->
                assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("vendor 'n8n': auth needs both header and value"));
    }
}
