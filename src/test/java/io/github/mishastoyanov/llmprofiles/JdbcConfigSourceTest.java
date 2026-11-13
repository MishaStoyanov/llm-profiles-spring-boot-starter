package io.github.mishastoyanov.llmprofiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import io.github.mishastoyanov.llmprofiles.autoconfigure.LlmProfilesAutoConfiguration;
import io.github.mishastoyanov.llmprofiles.source.JdbcConfigSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcConfigSourceTest {

    private final FakeVendor fake = new FakeVendor();
    private final List<LlmCallEvent> events = new CopyOnWriteArrayList<>();

    private static DataSource h2(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        return dataSource;
    }

    private ApplicationContextRunner runner(DataSource dataSource) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LlmProfilesAutoConfiguration.class))
                .withBean(FakeVendor.class, () -> fake)
                .withBean(DataSource.class, () -> dataSource)
                .withBean("recorder", LlmCallListener.class, () -> events::add)
                .withPropertyValues(
                        "llm.source.jdbc.enabled=true",
                        "llm.source.jdbc.create-tables=true",
                        "LOCAL_KEY=resolved-key",
                        "llm.vendors.local.type=fake",
                        "llm.profiles.summarizer.vendor=local",
                        "llm.profiles.summarizer.model=small");
    }

    @Test
    void rowsAreReadLikeYamlAndChangesArriveOnRefresh() {
        DataSource dataSource = h2("rows");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        runner(dataSource).run(context -> {
            LlmProfiles llm = context.getBean(LlmProfiles.class);
            assertThat(llm.names()).containsExactly("summarizer");

            jdbc.update("INSERT INTO llm_vendor (name, type, api_key, timeout, max_retries)"
                    + " VALUES ('remote', 'fake', '${LOCAL_KEY}', '90s', 1)");
            jdbc.update("INSERT INTO llm_profile (name, vendor, model, temperature, max_tokens, tags)"
                    + " VALUES ('triage', 'remote', 'gpt-4.1', 0.3, 500, '{\"feature\": \"triage\"}')");
            jdbc.update("INSERT INTO llm_price (vendor, model, input_per_million, output_per_million)"
                    + " VALUES ('remote', 'gpt-4.1', 2.00, 8.00)");
            assertThat(llm.refresh()).isTrue();

            llm.profile("triage").chat("hi");
            assertThat(fake.lastPrompt().getOptions().getModel()).isEqualTo("gpt-4.1");
            assertThat(fake.lastPrompt().getOptions().getTemperature()).isEqualTo(0.3);
            assertThat(fake.lastPrompt().getOptions().getMaxTokens()).isEqualTo(500);
            LlmCallEvent event = events.getLast();
            assertThat(event.tags()).containsEntry("feature", "triage");
            assertThat(event.price()).isEqualTo(new LlmPrice(new BigDecimal("2.0000"), new BigDecimal("8.0000")));

            assertThat(llm.refresh()).as("the same rows read again").isFalse();

            jdbc.update("UPDATE llm_profile SET model = 'gpt-5' WHERE name = 'triage'");
            assertThat(llm.refresh()).isTrue();
            llm.profile("triage").chat("hi");
            assertThat(fake.lastPrompt().getOptions().getModel()).isEqualTo("gpt-5");
            assertThat(fake.connected).as("the vendor did not change, so it was not reconnected")
                    .containsExactly("local", "remote");
        });
    }

    @Test
    void theSourceSeesTheVendorAsBound() {
        DataSource dataSource = h2("bound");
        JdbcConfigSource source = new JdbcConfigSource(new JdbcTemplate(dataSource),
                new MockEnvironment().withProperty("LOCAL_KEY", "resolved-key"), "llm_");
        source.createTables();
        new JdbcTemplate(dataSource).update("INSERT INTO llm_vendor (name, type, api_key, timeout)"
                + " VALUES ('remote', 'fake', '${LOCAL_KEY}', '90s')");

        LlmProperties.Vendor vendor = source.load().vendors().get("remote");
        assertThat(vendor.apiKey()).isEqualTo("resolved-key");
        assertThat(vendor.timeout()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void aKeyWrittenOutIsRefused() {
        DataSource dataSource = h2("plain");
        runner(dataSource).run(context -> {
            new JdbcTemplate(dataSource).update("INSERT INTO llm_vendor (name, type, api_key, auth_value)"
                    + " VALUES ('remote', 'fake', 'sk-live-123', '${NOWHERE_TO_BE_FOUND}')");

            assertThatThrownBy(context.getBean(LlmProfiles.class)::refresh)
                    .isInstanceOf(LlmProfilesConfigurationException.class)
                    .hasMessageContaining("llm_vendor 'remote': api_key must be a reference such as ${ANTHROPIC_API_KEY},"
                            + " not the secret itself")
                    .hasMessageNotContaining("sk-live-123");
        });
    }

    @Test
    void aReferenceThatResolvesToNothingIsRefused() {
        DataSource dataSource = h2("unresolved");
        runner(dataSource).run(context -> {
            new JdbcTemplate(dataSource).update("INSERT INTO llm_vendor (name, type, api_key)"
                    + " VALUES ('remote', 'fake', '${NOWHERE_TO_BE_FOUND}')");

            assertThatThrownBy(context.getBean(LlmProfiles.class)::refresh)
                    .hasMessageContaining("llm_vendor 'remote': api_key ${NOWHERE_TO_BE_FOUND} does not resolve");
        });
    }

    @Test
    void badTagsAreReportedByProfile() {
        DataSource dataSource = h2("tags");
        runner(dataSource).run(context -> {
            new JdbcTemplate(dataSource).update("INSERT INTO llm_profile (name, vendor, model, tags)"
                    + " VALUES ('triage', 'local', 'x', '[1, 2]')");

            assertThatThrownBy(context.getBean(LlmProfiles.class)::refresh)
                    .hasMessageContaining("llm_profile 'triage': tags must be a JSON object");
        });
    }

    @Test
    void tablesCanHaveAPrefixOfTheirOwn() {
        DataSource dataSource = h2("prefixed");
        runner(dataSource).withPropertyValues("llm.source.jdbc.table-prefix=config_").run(context -> {
            new JdbcTemplate(dataSource).update("INSERT INTO config_profile (name, vendor, model)"
                    + " VALUES ('triage', 'local', 'x')");
            context.getBean(LlmProfiles.class).refresh();
            assertThat(context.getBean(LlmProfiles.class).names()).containsExactly("summarizer", "triage");
        });
    }

    @Test
    void aPrefixThatIsNotANameIsRefused() {
        assertThatThrownBy(() -> new JdbcConfigSource(new JdbcTemplate(h2("x")), new MockEnvironment(), "a; DROP"))
                .hasMessageContaining("llm.source.jdbc.table-prefix must be a plain name prefix");
    }

    @Test
    void withoutADataSourceTheApplicationSaysSo() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LlmProfilesAutoConfiguration.class))
                .withPropertyValues("llm.source.jdbc.enabled=true")
                .run(context -> assertThat(context).getFailure()
                        .hasMessageContaining("llm.source.jdbc.enabled is true, but the application has no single DataSource"));
    }
}
