package io.github.mishastoyanov.llmprofiles;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;

import io.github.mishastoyanov.llmprofiles.autoconfigure.LlmProfilesAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StreamingAndChatModelTest {

    private final FakeVendor fake = new FakeVendor();
    private final List<LlmCallEvent> events = new CopyOnWriteArrayList<>();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LlmProfilesAutoConfiguration.class))
            .withBean(FakeVendor.class, () -> fake)
            .withBean("recorder", LlmCallListener.class, () -> events::add)
            .withPropertyValues(
                    "llm.vendors.local.type=fake",
                    "llm.vendors.local.timeout=500ms",
                    "llm.vendors.n8n.type=webhook",
                    "llm.vendors.n8n.base-url=http://localhost:1",
                    "llm.profiles.summarizer.vendor=local",
                    "llm.profiles.summarizer.model=small",
                    "llm.profiles.summarizer.temperature=0.2",
                    "llm.profiles.triage.vendor=n8n",
                    "llm.profiles.triage.path=/webhook/triage",
                    "llm.profiles.triage.request={\"prompt\": {{prompt}}}",
                    "llm.profiles.triage.response.text=$.output");

    private static LlmClient summarizer(org.springframework.context.ApplicationContext context) {
        return context.getBean(LlmProfiles.class).profile("summarizer");
    }

    // --- streaming -----------------------------------------------------------------------------

    @Test
    void aStreamDeliversTheTextAndIsReportedOnceAtTheEnd() {
        runner.run(context -> {
            List<String> pieces = summarizer(context).stream("hi").collectList().block();

            assertThat(pieces).containsExactly("echo", ": ", "hi");
            assertThat(events).hasSize(1);
            LlmCallEvent event = events.getFirst();
            assertThat(event.failed()).isFalse();
            assertThat(event.profile()).isEqualTo("summarizer");
            assertThat(event.vendor()).isEqualTo("local");
            assertThat(event.model()).as("reported by the first piece").isEqualTo("small-2026");
            assertThat(event.usage()).as("input from the first piece, output from the last")
                    .isEqualTo(new LlmUsage(12L, 3L, 15L));
            assertThat(event.firstTokenLatency()).isNotNull();
            assertThat(event.latency()).isGreaterThanOrEqualTo(event.firstTokenLatency());
            assertThat(fake.lastPrompt().getOptions().getModel()).isEqualTo("small");
            assertThat(fake.lastPrompt().getOptions().getTemperature()).isEqualTo(0.2);
        });
    }

    @Test
    void nothingIsSentUntilTheStreamIsSubscribedTo() {
        runner.run(context -> {
            Flux<String> stream = summarizer(context).stream("hi");
            assertThat(fake.prompts).isEmpty();

            stream.blockLast();
            stream.blockLast();
            assertThat(fake.prompts).as("each subscription is a call").hasSize(2);
            assertThat(events).hasSize(2);
        });
    }

    @Test
    void aCancelledStreamIsReportedAsCancelledWithWhatWasReportedSoFar() {
        runner.run(context -> {
            assertThat(summarizer(context).stream("hi").take(1).collectList().block()).containsExactly("echo");

            assertThat(events).hasSize(1);
            assertThat(events.getFirst().error()).isInstanceOf(CancellationException.class);
            assertThat(events.getFirst().usage().inputTokens()).isEqualTo(12L);
            assertThat(events.getFirst().usage().outputTokens()).isNull();
        });
    }

    @Test
    void aFailingStreamIsReportedAsFailed() {
        fake.streamed = prompt -> Flux.concat(
                Flux.just(FakeVendor.response("half", null, new org.springframework.ai.chat.metadata.EmptyUsage())),
                Flux.error(new IllegalStateException("connection reset")));
        runner.run(context -> {
            assertThatThrownBy(() -> summarizer(context).stream("hi").blockLast()).hasMessage("connection reset");

            assertThat(events).hasSize(1);
            assertThat(events.getFirst().error()).hasMessage("connection reset");
            assertThat(events.getFirst().usage().isUnknown()).isTrue();
        });
    }

    @Test
    void aStreamThatGoesQuietLongerThanTheTimeoutFails() {
        fake.streamed = prompt -> Flux.never();
        runner.run(context -> {
            assertThatThrownBy(() -> summarizer(context).stream("hi").blockLast(Duration.ofSeconds(5)))
                    .hasCauseInstanceOf(TimeoutException.class)
                    .hasMessageContaining("no part of the answer arrived within PT0.5S");
            assertThat(events.getFirst().failed()).isTrue();
        });
    }

    @Test
    void aWebhookProfileRefusesToStreamWhereStreamIsCalled() {
        runner.run(context -> assertThatThrownBy(() -> context.getBean(LlmProfiles.class).profile("triage").stream("hi"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("profile 'triage' uses vendor 'n8n' of type webhook, which does not stream; use call() or chat()"));
    }

    // --- overrides -----------------------------------------------------------------------------

    @Test
    void anOverrideSetToNullGivesTheProfileItsOwnValueBack() {
        runner.run(context -> {
            summarizer(context).withTemperature(0.9).withTemperature(null).chat("hi");
            assertThat(fake.lastPrompt().getOptions().getTemperature()).isEqualTo(0.2);
        });
    }

    @Test
    void aTimeoutThatIsNotPositiveStopsStartup() {
        runner.withPropertyValues("llm.vendors.local.timeout=0s").run(context -> assertThat(context).getFailure()
                .hasMessageContaining("vendor 'local': timeout must be positive, got PT0S"));
    }

    // --- as a Spring AI ChatModel --------------------------------------------------------------

    @Test
    void aChatClientOverAProfileIsAccountedForLikeAnyOtherCall() {
        runner.run(context -> {
            ChatClient chat = ChatClient.create(summarizer(context).asChatModel());

            assertThat(chat.prompt().user("hi").call().content()).isEqualTo("echo: hi");

            assertThat(fake.lastPrompt().getOptions().getModel()).isEqualTo("small");
            assertThat(fake.lastPrompt().getOptions().getTemperature()).isEqualTo(0.2);
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().profile()).isEqualTo("summarizer");
            assertThat(events.getFirst().usage().totalTokens()).isEqualTo(15L);
        });
    }

    @Test
    void optionsGivenWithThePromptWinOverTheProfile() {
        runner.run(context -> {
            ChatClient chat = ChatClient.create(summarizer(context).asChatModel());

            chat.prompt().user("hi").options(ChatOptions.builder().temperature(0.9)).call().content();

            assertThat(fake.lastPrompt().getOptions().getTemperature()).isEqualTo(0.9);
            assertThat(fake.lastPrompt().getOptions().getModel()).as("not given, so the profile's").isEqualTo("small");
        });
    }

    @Test
    void theClientsOverridesCarryOverIntoTheChatModel() {
        runner.run(context -> {
            ChatModel model = summarizer(context).withModel("large").asChatModel();

            ChatResponse response = model.call(new Prompt("hi"));

            assertThat(fake.lastPrompt().getOptions().getModel()).isEqualTo("large");
            assertThat(response.getMetadata().getModel()).as("the vendor's own response").isEqualTo("large-2026");
            assertThat(model.getDefaultOptions().getModel()).isEqualTo("large");
            assertThat(model.getDefaultOptions().getTemperature()).isEqualTo(0.2);
        });
    }

    @Test
    void aChatClientStreamsThroughTheProfileToo() {
        runner.run(context -> {
            ChatClient chat = ChatClient.create(summarizer(context).asChatModel());

            assertThat(chat.prompt().user("hi").stream().content().collectList().block())
                    .containsExactly("echo", ": ", "hi");
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().usage()).isEqualTo(new LlmUsage(12L, 3L, 15L));
        });
    }
}
