package io.github.mishastoyanov.llmprofiles;

import io.github.mishastoyanov.llmprofiles.autoconfigure.LlmProfilesAutoConfiguration;
import io.github.mishastoyanov.llmprofiles.names.bad.Misspelt;
import io.github.mishastoyanov.llmprofiles.names.good.Profiles;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfileNameTest {

    private final FakeVendor fake = new FakeVendor();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LlmProfilesAutoConfiguration.class))
            .withBean(FakeVendor.class, () -> fake)
            .withPropertyValues(
                    "llm.vendors.local.type=fake",
                    "llm.profiles.summarizer.vendor=local",
                    "llm.profiles.summarizer.model=small",
                    "llm.profiles.local-draft.vendor=local",
                    "llm.profiles.local-draft.model=draft");

    /** Stands in for the application's {@code @SpringBootApplication}, whose package is scanned. */
    @Configuration(proxyBeanMethods = false)
    @AutoConfigurationPackage(basePackageClasses = Profiles.class)
    static class GoodApplication {
    }

    @Configuration(proxyBeanMethods = false)
    @AutoConfigurationPackage(basePackageClasses = Misspelt.class)
    static class BadApplication {
    }

    @Test
    void aConstantFindsItsProfile() {
        runner.withUserConfiguration(GoodApplication.class).run(context -> {
            LlmProfiles llm = context.getBean(LlmProfiles.class);

            assertThat(llm.profile(Profiles.LOCAL_DRAFT).profile()).isEqualTo("local-draft");
            llm.profile(Profiles.SUMMARIZER).chat("hi");
            assertThat(fake.lastPrompt().getOptions().getModel()).isEqualTo("small");
        });
    }

    @Test
    void aConstantWithoutAProfileStopsStartup() {
        runner.withUserConfiguration(BadApplication.class).run(context -> assertThat(context).getFailure()
                .isInstanceOf(LlmProfilesConfigurationException.class)
                .hasMessageContaining("Profile names in code do not match llm.profiles")
                .hasMessageContaining(Misspelt.class.getName() + ".SUMARIZER: profile 'SUMARIZER' is not declared")
                .hasMessageNotContaining(".SUMMARIZER:"));
    }

    @Test
    void aConstantsProfileCannotBeRefreshedAway() {
        runner.withUserConfiguration(GoodApplication.class).run(context -> {
            context.getEnvironment().getPropertySources().remove("test");
            TestPropertyValues.of(
                    "llm.vendors.local.type=fake",
                    "llm.profiles.summarizer.vendor=local",
                    "llm.profiles.summarizer.model=small").applyTo(context.getEnvironment());

            assertThatThrownBy(context.getBean(LlmProfiles.class)::refresh)
                    .hasMessageContaining("profile 'LOCAL_DRAFT' is named in code");
        });
    }
}
