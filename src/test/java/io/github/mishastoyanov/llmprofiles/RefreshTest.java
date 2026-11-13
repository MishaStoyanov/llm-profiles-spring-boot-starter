package io.github.mishastoyanov.llmprofiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.github.mishastoyanov.llmprofiles.autoconfigure.LlmProfilesAutoConfiguration;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.core.env.ConfigurableEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefreshTest {

    private final FakeVendor fake = new FakeVendor();
    private final AtomicReference<LlmSettings> database = new AtomicReference<>(LlmSettings.EMPTY);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LlmProfilesAutoConfiguration.class))
            .withBean(FakeVendor.class, () -> fake)
            .withBean(LlmConfigSource.class, () -> database::get)
            .withPropertyValues(
                    "llm.vendors.local.type=fake",
                    "llm.profiles.summarizer.vendor=local",
                    "llm.profiles.summarizer.model=small",
                    "llm.profiles.summarizer.temperature=0.2");

    @Getter
    @RequiredArgsConstructor
    static class ReportService {
        private final LlmClient summarizer;
    }

    private static LlmProperties.Vendor vendor(String type, String baseUrl) {
        return new LlmProperties.Vendor(type, null, baseUrl, null, null, null);
    }

    private static LlmProperties.Profile profile(String vendor, String model) {
        return new LlmProperties.Profile(vendor, model, null, null, null, null, null, null);
    }

    @Test
    void aSourceReplacesAProfileWholeAndAddsItsOwn() {
        database.set(new LlmSettings(null, Map.of(
                "summarizer", profile("local", "large"),
                "triage", profile("local", "tiny")), null));

        runner.run(context -> {
            LlmProfiles llm = context.getBean(LlmProfiles.class);

            assertThat(llm.names()).containsExactly("summarizer", "triage");
            llm.profile("summarizer").chat("hi");
            assertThat(fake.lastPrompt().getOptions().getModel()).isEqualTo("large");
            assertThat(fake.lastPrompt().getOptions().getTemperature())
                    .as("replaced whole: the YAML temperature does not survive").isNull();
            llm.profile("triage").chat("hi");
            assertThat(fake.lastPrompt().getOptions().getModel()).isEqualTo("tiny");
        });
    }

    @Test
    void anInjectedClientFollowsARefresh() {
        runner.withBean(ReportService.class).run(context -> {
            LlmClient summarizer = context.getBean(ReportService.class).getSummarizer();
            LlmProfiles llm = context.getBean(LlmProfiles.class);

            summarizer.chat("before");
            assertThat(fake.lastPrompt().getOptions().getModel()).isEqualTo("small");

            database.set(new LlmSettings(null, Map.of("summarizer", profile("local", "large")), null));
            assertThat(llm.refresh()).isTrue();

            summarizer.chat("after");
            assertThat(fake.lastPrompt().getOptions().getModel()).isEqualTo("large");
            assertThat(llm.refresh()).as("nothing changed since").isFalse();
        });
    }

    @Test
    void aVendorKeepsItsConnectionUnlessItsSettingsChange() {
        runner.run(context -> {
            LlmProfiles llm = context.getBean(LlmProfiles.class);
            assertThat(fake.connected).containsExactly("local");

            database.set(new LlmSettings(Map.of("second", vendor("fake", null)),
                    Map.of("triage", profile("second", "tiny")), null));
            llm.refresh();
            assertThat(fake.connected).as("only the new vendor is connected").containsExactly("local", "second");

            database.set(new LlmSettings(Map.of("local", vendor("fake", "http://elsewhere")), null, null));
            llm.refresh();
            assertThat(fake.connected).as("local changed, second is gone")
                    .containsExactly("local", "second", "local");
            assertThat(llm.names()).containsExactly("summarizer");
        });
    }

    @Test
    void aProfileOnlyInTheSourceCanGoAwayAndThenIsUnknown() {
        database.set(new LlmSettings(null, Map.of("triage", profile("local", "tiny")), null));
        runner.run(context -> {
            LlmProfiles llm = context.getBean(LlmProfiles.class);
            LlmClient triage = llm.profile("triage");

            database.set(LlmSettings.EMPTY);
            llm.refresh();

            assertThatThrownBy(() -> triage.chat("hi"))
                    .isInstanceOf(UnknownProfileException.class)
                    .hasMessage("No LLM profile named 'triage'. Declared profiles: [summarizer]");
        });
    }

    @Test
    void anInvalidRefreshChangesNothing() {
        runner.run(context -> {
            LlmProfiles llm = context.getBean(LlmProfiles.class);

            database.set(new LlmSettings(Map.of("broken", vendor("carrier-pigeon", null)),
                    Map.of("summarizer", profile("local", "large"), "orphan", profile("missing", "x")), null));
            assertThatThrownBy(llm::refresh)
                    .isInstanceOf(LlmProfilesConfigurationException.class)
                    .hasMessageContaining("vendor 'broken': no adapter for type 'carrier-pigeon'")
                    .hasMessageContaining("profile 'orphan': vendor 'missing' is not declared");

            llm.profile("summarizer").chat("hi");
            assertThat(fake.lastPrompt().getOptions().getModel()).isEqualTo("small");
            assertThat(llm.names()).containsExactly("summarizer");
        });
    }

    @Test
    void aSourceThatFailsToLoadChangesNothing() {
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        runner.withBean("flaky", LlmConfigSource.class, () -> () -> {
            if (failure.get() != null) {
                throw failure.get();
            }
            return LlmSettings.EMPTY;
        }).run(context -> {
            LlmProfiles llm = context.getBean(LlmProfiles.class);
            failure.set(new IllegalStateException("database down"));

            assertThatThrownBy(llm::refresh)
                    .isInstanceOf(LlmProfilesConfigurationException.class)
                    .hasMessageContaining("could not read llm configuration from")
                    .hasMessageContaining("database down");
            assertThat(llm.names()).containsExactly("summarizer");
        });
    }

    @Test
    void aProfileNamedInCodeCannotBeRemoved() {
        runner.withBean(ReportService.class).run(context -> {
            LlmProfiles llm = context.getBean(LlmProfiles.class);
            ConfigurableEnvironment environment = context.getEnvironment();

            // As a Spring Cloud refresh would leave the environment: summarizer gone from YAML.
            environment.getPropertySources().remove("test");
            TestPropertyValues.of("llm.vendors.local.type=fake").applyTo(environment);

            assertThatThrownBy(llm::refresh)
                    .isInstanceOf(LlmProfilesConfigurationException.class)
                    .hasMessageContaining("profile 'summarizer' is named in code, so it cannot be removed");
            assertThat(context.getBean(ReportService.class).getSummarizer().chat("still here")).isEqualTo("echo: still here");
        });
    }

    @Test
    void theApplicationsOwnConfigurationIsReadAgainOnRefresh() {
        runner.run(context -> {
            LlmProfiles llm = context.getBean(LlmProfiles.class);
            TestPropertyValues.of("llm.profiles.summarizer.model=medium").applyTo(context.getEnvironment());

            assertThat(llm.refresh()).isTrue();
            llm.profile("summarizer").chat("hi");
            assertThat(fake.lastPrompt().getOptions().getModel()).isEqualTo("medium");
            assertThat(fake.lastPrompt().getOptions().getTemperature()).isEqualTo(0.2);
        });
    }

    @Test
    void pricesComeFromSourcesToo() {
        runner.withBean(LlmCallListener.class, () -> event -> fake.connected.add("priced=" + event.priced()))
                .run(context -> {
                    LlmProfiles llm = context.getBean(LlmProfiles.class);
                    llm.profile("summarizer").chat("hi");

                    database.set(new LlmSettings(null, null, Map.of("local", Map.of("small",
                            new LlmProperties.Price(BigDecimal.ONE, BigDecimal.TEN)))));
                    llm.refresh();
                    llm.profile("summarizer").chat("hi");

                    assertThat(fake.connected).containsExactly("local", "priced=false", "priced=true");
                });
    }

    @Test
    void aRefreshIntervalRefreshesOnItsOwn() {
        runner.withPropertyValues("llm.source.refresh-interval=50ms").run(context -> {
            LlmProfiles llm = context.getBean(LlmProfiles.class);
            database.set(new LlmSettings(null, Map.of("triage", profile("local", "tiny")), null));

            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!llm.names().contains("triage") && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(llm.names()).contains("triage");
        });
    }
}
