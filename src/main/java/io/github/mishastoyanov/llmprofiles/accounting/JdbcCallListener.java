package io.github.mishastoyanov.llmprofiles.accounting;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

import io.github.mishastoyanov.llmprofiles.LlmCallEvent;
import io.github.mishastoyanov.llmprofiles.LlmCallListener;
import io.github.mishastoyanov.llmprofiles.LlmUsage;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * One row per call. The table's definition is in {@value #SCHEMA} on the classpath, written for
 * PostgreSQL and H2; copy it into a migration, or set {@code create-table} to try things out.
 * <p>
 * A row carries the price per million tokens as it was at the time of the call, not a total:
 * {@code input_tokens * input_per_million + output_tokens * output_per_million}, over a million,
 * is the cost, and a stored total could not tell a cheap call from a mispriced one. Null rates
 * mean the model was not priced. Null counts mean the provider did not say.
 * <p>
 * The row is written after the call, on the calling thread unless {@code llm.accounting.async} is
 * on: a slow database then slows calls. A failing one is logged and leaves them alone.
 */
public class JdbcCallListener implements LlmCallListener {

    public static final String SCHEMA = "io/github/mishastoyanov/llmprofiles/accounting/llm_call.sql";

    /** A table name, optionally with a schema; anything else would be pasted into SQL. */
    private static final Pattern TABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");
    private static final int ERROR_LENGTH = 1000;

    private final JdbcTemplate jdbc;
    private final String table;
    private final String insert;

    public JdbcCallListener(JdbcTemplate jdbc, String table) {
        if (table == null || !TABLE.matcher(table).matches()) {
            throw new IllegalArgumentException("llm.accounting.jdbc.table must be a plain table name,"
                    + " optionally with a schema, got '" + table + "'");
        }
        this.jdbc = jdbc;
        this.table = table;
        this.insert = "INSERT INTO " + table + " (started_at, profile, vendor, model, input_tokens, output_tokens,"
                + " total_tokens, reasoning_tokens, input_per_million, output_per_million, latency_ms, first_token_ms,"
                + " error, tags) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    }

    /** {@code CREATE TABLE IF NOT EXISTS}, from {@value #SCHEMA} with the configured name. */
    public void createTable() {
        jdbc.execute(schema().replace("llm_call", table));
    }

    @Override
    public void onCall(LlmCallEvent event) {
        LlmUsage usage = event.usage() == null ? LlmUsage.UNKNOWN : event.usage();
        jdbc.update(insert, statement -> {
            statement.setObject(1, event.startedAt().atOffset(ZoneOffset.UTC));
            statement.setString(2, event.profile());
            statement.setString(3, event.vendor());
            statement.setString(4, event.model());
            setLong(statement, 5, usage.inputTokens());
            setLong(statement, 6, usage.outputTokens());
            setLong(statement, 7, usage.totalTokens());
            setLong(statement, 8, usage.reasoningTokens());
            statement.setBigDecimal(9, event.priced() ? event.price().inputPerMillion() : null);
            statement.setBigDecimal(10, event.priced() ? event.price().outputPerMillion() : null);
            statement.setLong(11, event.latency().toMillis());
            setLong(statement, 12, event.firstTokenLatency() == null ? null : event.firstTokenLatency().toMillis());
            statement.setString(13, error(event));
            statement.setString(14, event.tags().isEmpty() ? null : JsonMapper.shared().writeValueAsString(event.tags()));
        });
    }

    private static void setLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static String error(LlmCallEvent event) {
        if (!event.failed()) {
            return null;
        }
        String text = event.error().getClass().getName() + ": " + event.error().getMessage();
        return text.length() <= ERROR_LENGTH ? text : text.substring(0, ERROR_LENGTH);
    }

    private static String schema() {
        try (InputStream in = JdbcCallListener.class.getClassLoader().getResourceAsStream(SCHEMA)) {
            if (in == null) {
                throw new IllegalStateException(SCHEMA + " is missing from the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + SCHEMA, e);
        }
    }
}
