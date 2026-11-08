package io.github.mishastoyanov.llmprofiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import io.github.mishastoyanov.llmprofiles.accounting.JdbcCallListener;
import io.github.mishastoyanov.llmprofiles.accounting.LogCallListener;
import io.github.mishastoyanov.llmprofiles.accounting.MicrometerCallListener;
import io.github.mishastoyanov.llmprofiles.autoconfigure.LlmProfilesAutoConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountingTest {

    private final FakeVendor fake = new FakeVendor();
    private final List<LlmCallEvent> events = new CopyOnWriteArrayList<>();

    /** The fake answers with 12 input and 3 output tokens, and names the model "<asked>-2026". */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LlmProfilesAutoConfiguration.class))
            .withBean(FakeVendor.class, () -> fake)
            .withBean("recorder", LlmCallListener.class, () -> events::add)
            .withPropertyValues(
                    "llm.vendors.local.type=fake",
                    "llm.profiles.summarizer.vendor=local",
                    "llm.profiles.summarizer.model=small",
                    "llm.profiles.summarizer.tags.feature=summaries",
                    "llm.profiles.summarizer.tags.team=docs",
                    "llm.profiles.reviewer.vendor=local",
                    "llm.profiles.reviewer.model=large",
                    "llm.prices.local.small.input-per-million=1.00",
                    "llm.prices.local.small.output-per-million=5.00");

    private static LlmClient profile(ApplicationContext context, String name) {
        return context.getBean(LlmProfiles.class).profile(name);
    }

    // --- prices ------------------------------------------------------------------------------

    @Test
    void aCallCarriesThePriceOfItsModelAndWhatItCost() {
        runner.run(context -> {
            Instant before = Instant.now();
            profile(context, "summarizer").chat("hi");

            LlmCallEvent event = events.getFirst();
            assertThat(event.model()).isEqualTo("small-2026");
            assertThat(event.priced()).as("found under the model asked for").isTrue();
            assertThat(event.price()).isEqualTo(new LlmPrice(new BigDecimal("1.00"), new BigDecimal("5.00")));
            assertThat(event.inputCost()).isEqualByComparingTo("0.000012");
            assertThat(event.outputCost()).isEqualByComparingTo("0.000015");
            assertThat(event.cost()).isEqualByComparingTo("0.000027");
            assertThat(event.startedAt()).isBetween(before, Instant.now());
        });
    }

    @Test
    void thePriceOfTheModelThatAnsweredWinsOverTheOneAskedFor() {
        runner.withPropertyValues(
                        "llm.prices.local[small-2026].input-per-million=2",
                        "llm.prices.local[small-2026].output-per-million=10")
                .run(context -> {
                    profile(context, "summarizer").chat("hi");
                    assertThat(events.getFirst().cost()).isEqualByComparingTo("0.000054");
                });
    }

    @Test
    void aModelNobodyPricedIsUnpricedNotFree() {
        runner.run(context -> {
            profile(context, "reviewer").chat("hi");

            assertThat(events.getFirst().priced()).isFalse();
            assertThat(events.getFirst().cost()).isNull();
            assertThat(LogCallListener.format(events.getFirst())).contains("cost=unpriced")
                    .doesNotContain("(input");
        });
    }

    @Test
    void whenOnlyOneKindOfTokenIsKnownOnlyItsCostIs() {
        fake.answer = prompt -> FakeVendor.response("ok", "small", new DefaultUsage(12, 0));
        runner.run(context -> {
            profile(context, "summarizer").chat("hi");

            LlmCallEvent event = events.getFirst();
            assertThat(event.priced()).isTrue();
            assertThat(event.inputCost()).isEqualByComparingTo("0.000012");
            assertThat(event.outputCost()).as("output tokens not reported").isNull();
            assertThat(event.cost()).as("half a bill is not the bill").isNull();
            assertThat(LogCallListener.format(event)).contains("input=12 output=? cost=? (input 0.000012, output ?)");
        });
    }

    @Test
    void everyPriceProblemStopsStartup() {
        runner.withPropertyValues(
                        "llm.prices.nowhere.x.input-per-million=1",
                        "llm.prices.nowhere.x.output-per-million=1",
                        "llm.prices.local.half.input-per-million=1",
                        "llm.prices.local.negative.input-per-million=-1",
                        "llm.prices.local.negative.output-per-million=1")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("prices for vendor 'nowhere': no such vendor under llm.vendors")
                        .hasMessageContaining("price of 'half' at vendor 'local': both input-per-million and"
                                + " output-per-million are required")
                        .hasMessageContaining("price of 'negative' at vendor 'local': a price cannot be negative"));
    }

    // --- tags --------------------------------------------------------------------------------

    @Test
    void aClientsTagsLieOverItsProfiles() {
        runner.run(context -> {
            LlmClient tagged = profile(context, "summarizer").withTag("team", "billing").withTag("job", "42");
            tagged.chat("hi");
            tagged.withTag("job", null).chat("hi");
            profile(context, "summarizer").chat("hi");

            assertThat(events.get(0).tags()).containsExactly(
                    Map.entry("feature", "summaries"), Map.entry("team", "billing"), Map.entry("job", "42"));
            assertThat(events.get(1).tags()).as("a null value removes the client's tag")
                    .containsOnlyKeys("feature", "team").containsEntry("team", "billing");
            assertThat(events.get(2).tags()).as("the profile itself is untouched")
                    .containsExactly(Map.entry("feature", "summaries"), Map.entry("team", "docs"));
        });
    }

    // --- log ---------------------------------------------------------------------------------

    @Test
    void theLogLineSaysWhatIsKnownAndOnlyThat() {
        runner.run(context -> {
            profile(context, "summarizer").withTag("job", "42").chat("hi");

            assertThat(LogCallListener.format(events.getFirst()))
                    .startsWith("profile=summarizer vendor=local model=small-2026 latency=")
                    .contains(" input=12 output=3 cost=0.000027 (input 0.000012, output 0.000015)"
                            + " tags={feature=summaries, team=docs, job=42}")
                    .doesNotContain("failed=");
        });
    }

    @Test
    void theLogSinkIsOffUntilSwitchedOn() {
        runner.run(context -> assertThat(context).doesNotHaveBean(LogCallListener.class));
        runner.withPropertyValues("llm.accounting.log.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(LogCallListener.class));
    }

    // --- Micrometer --------------------------------------------------------------------------

    @Test
    void metersCountCallsTokensAndKnownCost() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        runner.withBean(MeterRegistry.class, () -> meters).run(context -> {
            profile(context, "summarizer").chat("hi");
            profile(context, "summarizer").chat("hi");
            profile(context, "reviewer").chat("hi");

            assertThat(meters.get(MicrometerCallListener.CALLS)
                    .tags("profile", "summarizer", "vendor", "local", "model", "small-2026",
                            "outcome", "success", "priced", "true")
                    .timer().count()).isEqualTo(2);
            assertThat(meters.get(MicrometerCallListener.TOKENS).tags("profile", "summarizer", "type", "input")
                    .counter().count()).isEqualTo(24);
            assertThat(meters.get(MicrometerCallListener.COST).tags("profile", "summarizer", "type", "input")
                    .counter().count()).isCloseTo(0.000024, org.assertj.core.data.Offset.offset(1e-12));
            assertThat(meters.get(MicrometerCallListener.COST).tags("profile", "summarizer", "type", "output")
                    .counter().count()).isCloseTo(0.00003, org.assertj.core.data.Offset.offset(1e-12));
            assertThat(meters.get(MicrometerCallListener.CALLS).tags("profile", "reviewer", "priced", "false")
                    .timer().count()).isEqualTo(1);
            assertThat(meters.find(MicrometerCallListener.COST).tags("profile", "reviewer").counter())
                    .as("an unpriced call adds nothing to the cost").isNull();
        });
    }

    @Test
    void theKnownHalfOfACostIsMeteredAndTheUnknownOneIsNot() {
        fake.answer = prompt -> FakeVendor.response("ok", "small", new DefaultUsage(12, 0));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        runner.withBean(MeterRegistry.class, () -> meters).run(context -> {
            profile(context, "summarizer").chat("hi");

            assertThat(meters.get(MicrometerCallListener.COST).tags("type", "input").counter().count())
                    .isCloseTo(0.000012, org.assertj.core.data.Offset.offset(1e-12));
            assertThat(meters.find(MicrometerCallListener.COST).tags("type", "output").counter()).isNull();
        });
    }

    @Test
    void aCancelledStreamIsMeteredAsCancelled() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        runner.withBean(MeterRegistry.class, () -> meters).run(context -> {
            profile(context, "summarizer").stream("hi").take(1).blockLast();

            assertThat(meters.get(MicrometerCallListener.CALLS).tags("outcome", "cancelled").timer().count())
                    .isEqualTo(1);
        });
    }

    @Test
    void metersCanBeSwitchedOff() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        runner.withBean(MeterRegistry.class, () -> meters)
                .withPropertyValues("llm.accounting.metrics.enabled=false")
                .run(context -> {
                    profile(context, "summarizer").chat("hi");
                    assertThat(meters.find(MicrometerCallListener.CALLS).meters()).isEmpty();
                });
    }

    // --- JDBC --------------------------------------------------------------------------------

    private static DataSource h2(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        return dataSource;
    }

    @Test
    void everyCallIsARowWithTheRatesOfItsTime() {
        DataSource dataSource = h2("rows");
        runner.withBean(DataSource.class, () -> dataSource)
                .withPropertyValues("llm.accounting.jdbc.enabled=true", "llm.accounting.jdbc.create-table=true")
                .run(context -> {
                    profile(context, "summarizer").withTag("job", "42").chat("hi");
                    profile(context, "reviewer").chat("hi");
                    fake.streamed = prompt -> Flux.error(new IllegalStateException("connection reset"));
                    assertThatThrownBy(() -> profile(context, "summarizer").stream("hi").blockLast());

                    List<Map<String, Object>> rows = new JdbcTemplate(dataSource)
                            .queryForList("SELECT * FROM llm_call ORDER BY started_at");
                    assertThat(rows).hasSize(3);

                    Map<String, Object> priced = rows.get(0);
                    assertThat(priced).containsEntry("PROFILE", "summarizer").containsEntry("VENDOR", "local")
                            .containsEntry("MODEL", "small-2026")
                            .containsEntry("INPUT_TOKENS", 12L).containsEntry("OUTPUT_TOKENS", 3L)
                            .containsEntry("TOTAL_TOKENS", 15L)
                            .containsEntry("TAGS", "{\"feature\":\"summaries\",\"team\":\"docs\",\"job\":\"42\"}");
                    assertThat((BigDecimal) priced.get("INPUT_PER_MILLION")).isEqualByComparingTo("1.00");
                    assertThat((BigDecimal) priced.get("OUTPUT_PER_MILLION")).isEqualByComparingTo("5.00");
                    assertThat(priced.get("ERROR")).isNull();

                    assertThat(rows.get(1).get("INPUT_PER_MILLION")).as("unpriced: no rate, not zero").isNull();

                    assertThat(rows.get(2).get("ERROR")).isEqualTo("java.lang.IllegalStateException: connection reset");
                    assertThat(rows.get(2).get("INPUT_TOKENS")).isNull();
                });
    }

    @Test
    void theRowsCanGoToATableOfAnotherName() {
        DataSource dataSource = h2("named");
        runner.withBean(DataSource.class, () -> dataSource)
                .withPropertyValues("llm.accounting.jdbc.enabled=true", "llm.accounting.jdbc.create-table=true",
                        "llm.accounting.jdbc.table=model_calls")
                .run(context -> {
                    profile(context, "summarizer").chat("hi");
                    assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT COUNT(*) FROM model_calls",
                            Integer.class)).isEqualTo(1);
                });
    }

    @Test
    void aTableNameThatIsNotOneStopsStartup() {
        runner.withBean(DataSource.class, () -> h2("bad"))
                .withPropertyValues("llm.accounting.jdbc.enabled=true", "llm.accounting.jdbc.table=calls; DROP TABLE x")
                .run(context -> assertThat(context).getFailure()
                        .hasMessageContaining("llm.accounting.jdbc.table must be a plain table name"));
    }

    @Test
    void theJdbcSinkWithoutADatabaseSaysSo() {
        runner.withPropertyValues("llm.accounting.jdbc.enabled=true")
                .run(context -> assertThat(context).getFailure()
                        .hasMessageContaining("llm.accounting.jdbc.enabled is true, but the application has no single"
                                + " DataSource"));
    }

    @Test
    void theSchemaShipsWithTheLibrary() {
        assertThat(getClass().getClassLoader().getResource(JdbcCallListener.SCHEMA)).isNotNull();
    }
}
