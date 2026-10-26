package io.github.mishastoyanov.llmprofiles;

import java.util.ArrayList;
import java.util.List;

import io.github.mishastoyanov.llmprofiles.autoconfigure.LlmProfilesAutoConfiguration;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmProfilesTest {

    private final FakeVendor fake = new FakeVendor();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LlmProfilesAutoConfiguration.class))
            .withBean(FakeVendor.class, () -> fake)
            .withPropertyValues(
                    "llm.vendors.local.type=fake",
                    "llm.profiles.summarizer.vendor=local",
                    "llm.profiles.summarizer.model=small",
                    "llm.profiles.summarizer.temperature=0.2",
                    "llm.profiles.local-draft.vendor=local",
                    "llm.profiles.local-draft.model=draft",
                    "llm.profiles.reviewer.vendor=local",
                    "llm.profiles.reviewer.model=large",
                    "llm.profiles.reviewer.max-tokens=4000");

    @Getter
    @RequiredArgsConstructor
    static class ReportService {
        private final LlmClient summarizer;
        private final LlmClient localDraft;
    }

    @Getter
    @RequiredArgsConstructor
    static class NamedOtherwise {
        @LlmProfile("reviewer")
        private final LlmClient client;
    }

    @RequiredArgsConstructor
    static class Misspelt {
        private final LlmClient sumarizer;
    }

    @RequiredArgsConstructor
    static class LazyMisspelt {
        @LlmProfile("reveiwer")
        private final LlmClient client;
    }

    @RequiredArgsConstructor
    static class WrongType {
        @LlmProfile("summarizer")
        private final String client;
    }

    /** Takes a client as an argument, which is not an injection point and must not be checked. */
    static class Helper {
        String ask(LlmClient anyClient, String prompt) {
            return anyClient.chat(prompt);
        }
    }

    @Test
    void theFieldNameIsTheProfile() {
        runner.withBean(ReportService.class).run(context -> {
            ReportService service = context.getBean(ReportService.class);

            assertThat(service.getSummarizer().profile()).isEqualTo("summarizer");
            assertThat(service.getLocalDraft().profile()).as("localDraft finds local-draft").isEqualTo("local-draft");
        });
    }

    @Test
    void anAnnotatedFieldIsNamedByItsAnnotation() {
        runner.withBean(NamedOtherwise.class).run(context ->
                assertThat(context.getBean(NamedOtherwise.class).getClient().profile()).isEqualTo("reviewer"));
    }

    @Test
    void aCallReportsWhatItTook() {
        runner.withBean(ReportService.class).run(context -> {
            LlmResponse response = context.getBean(ReportService.class).getSummarizer().call("hello");

            assertThat(response.text()).isEqualTo("echo: hello");
            assertThat(response.profile()).isEqualTo("summarizer");
            assertThat(response.vendor()).isEqualTo("local");
            assertThat(response.model()).as("the model the provider says answered").isEqualTo("small-2026");
            assertThat(response.usage()).isEqualTo(new LlmUsage(12L, 3L, 15L));
            assertThat(response.latency().isNegative()).isFalse();
            assertThat(fake.lastPrompt().getOptions().getModel()).isEqualTo("small");
            assertThat(fake.lastPrompt().getOptions().getTemperature()).isEqualTo(0.2);
        });
    }

    @Test
    void overridesApplyToTheCallAndNotToTheProfile() {
        runner.run(context -> {
            LlmProfiles llm = context.getBean(LlmProfiles.class);

            llm.profile("reviewer").withModel("larger").withTemperature(0.1).withMaxTokens(100).chat("diff");
            assertThat(fake.lastPrompt().getOptions().getModel()).isEqualTo("larger");
            assertThat(fake.lastPrompt().getOptions().getTemperature()).isEqualTo(0.1);
            assertThat(fake.lastPrompt().getOptions().getMaxTokens()).isEqualTo(100);

            llm.profile("reviewer").chat("diff");
            assertThat(fake.lastPrompt().getOptions().getModel()).isEqualTo("large");
            assertThat(fake.lastPrompt().getOptions().getTemperature()).isNull();
            assertThat(fake.lastPrompt().getOptions().getMaxTokens()).isEqualTo(4000);
        });
    }

    @Test
    void usageTheProviderDidNotReportIsUnknownNotZero() {
        fake.answer = prompt -> FakeVendor.response("ok", null, new EmptyUsage());
        runner.run(context -> {
            LlmResponse response = context.getBean(LlmProfiles.class).profile("summarizer").call("hi");

            assertThat(response.usage().isUnknown()).isTrue();
            assertThat(response.usage().inputTokens()).isNull();
            assertThat(response.model()).as("falls back to the requested model").isEqualTo("small");
        });
    }

    @Test
    void everyMisspeltProfileStopsStartupInOneMessage() {
        runner.withBean(Misspelt.class)
                // Lazy and never asked for, so injection would never see it: only the scan can.
                .withBean("lazyMisspelt", LazyMisspelt.class, () -> new LazyMisspelt(null),
                        definition -> definition.setLazyInit(true))
                .run(context -> assertThat(context).getFailure()
                        .isInstanceOf(LlmProfilesConfigurationException.class)
                        .hasMessageContaining("declared: [summarizer, local-draft, reviewer]")
                        .hasMessageContaining("Misspelt(constructor) parameter 'sumarizer': profile 'sumarizer' is not declared")
                        .hasMessageContaining("LazyMisspelt(constructor) parameter 'client': profile 'reveiwer' is not declared"));
    }

    @Test
    void annotationOnSomethingOtherThanAClientStopsStartup() {
        runner.withBean(WrongType.class).run(context -> assertThat(context).getFailure()
                .hasMessageContaining("@LlmProfile goes on an LlmClient, not on java.lang.String"));
    }

    @Test
    void aMethodTakingAClientArgumentIsNotAnInjectionPoint() {
        runner.withBean(Helper.class).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void codeFindsAProfileByEitherSpelling() {
        runner.run(context -> {
            LlmProfiles llm = context.getBean(LlmProfiles.class);

            assertThat(llm.profile("localDraft").profile()).isEqualTo("local-draft");
            assertThat(llm.names()).containsExactly("summarizer", "local-draft", "reviewer");
            assertThatThrownBy(() -> llm.profile("triage"))
                    .isInstanceOf(UnknownProfileException.class)
                    .hasMessage("No LLM profile named 'triage'. Declared profiles: [summarizer, local-draft, reviewer]");
        });
    }

    @Test
    void everyConfigurationProblemIsReportedAtOnce() {
        runner.withPropertyValues(
                        "llm.vendors.n8n.type=webhook",
                        "llm.vendors.mystery.type=carrier-pigeon",
                        "llm.vendors.untyped.base-url=http://nowhere",
                        "llm.profiles.orphan.vendor=missing",
                        "llm.profiles.orphan.model=x",
                        "llm.profiles.modelless.vendor=local",
                        "llm.profiles.local_draft.vendor=local",
                        "llm.profiles.local_draft.model=x")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .isInstanceOf(LlmProfilesConfigurationException.class)
                        .hasMessageContaining("vendor 'n8n': base-url is required for type webhook")
                        .hasMessageContaining("vendor 'mystery': no adapter for type 'carrier-pigeon'")
                        .hasMessageContaining("vendor 'untyped': type is not set")
                        .hasMessageContaining("profile 'orphan': vendor 'missing' is not declared under llm.vendors")
                        .hasMessageContaining("profile 'modelless': model is not set")
                        .hasMessageContaining("profiles 'local-draft' and 'local_draft' are one name"));
    }

    @Test
    void listenersSeeSuccessesAndFailuresAndCannotBreakTheCall() {
        List<LlmCallEvent> events = new ArrayList<>();
        runner.withBean("recorder", LlmCallListener.class, () -> events::add)
                .withBean("broken", LlmCallListener.class, () -> event -> {
                    throw new IllegalStateException("listener bug");
                })
                .run(context -> {
                    LlmClient summarizer = context.getBean(LlmProfiles.class).profile("summarizer");

                    assertThat(summarizer.chat("one")).isEqualTo("echo: one");

                    fake.answer = prompt -> {
                        throw new IllegalStateException("provider down");
                    };
                    assertThatThrownBy(() -> summarizer.chat("two")).hasMessage("provider down");

                    assertThat(events).hasSize(2);
                    assertThat(events.getFirst().failed()).isFalse();
                    assertThat(events.getFirst().usage().totalTokens()).isEqualTo(15L);
                    assertThat(events.getLast().failed()).isTrue();
                    assertThat(events.getLast().usage()).isEqualTo(LlmUsage.UNKNOWN);
                });
    }

    @Test
    void builtInVendorTypesBuildWithoutTouchingTheNetwork() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LlmProfilesAutoConfiguration.class))
                .withPropertyValues(
                        "llm.vendors.anthropic.type=anthropic",
                        "llm.vendors.anthropic.api-key=test-key",
                        "llm.vendors.vllm.type=openai-compatible",
                        "llm.vendors.vllm.base-url=https://llm.internal/v1",
                        "llm.vendors.vllm.api-key=test-key",
                        "llm.vendors.ollama.type=ollama",
                        "llm.profiles.summarizer.vendor=anthropic",
                        "llm.profiles.summarizer.model=claude-haiku-4-5",
                        "llm.profiles.draft.vendor=ollama",
                        "llm.profiles.draft.model=llama3",
                        "llm.profiles.prod.vendor=vllm",
                        "llm.profiles.prod.model=qwen")
                .run(context -> assertThat(context.getBean(LlmProfiles.class).names())
                        .containsExactly("summarizer", "draft", "prod"));
    }

    @Test
    void aBuiltInVendorWithoutItsKeySaysSo() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LlmProfilesAutoConfiguration.class))
                .withPropertyValues("llm.vendors.anthropic.type=anthropic")
                .run(context -> assertThat(context).getFailure()
                        .hasMessageContaining("vendor 'anthropic': api-key is required for type anthropic"));
    }
}
