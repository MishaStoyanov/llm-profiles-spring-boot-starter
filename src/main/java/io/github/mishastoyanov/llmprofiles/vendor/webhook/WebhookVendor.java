package io.github.mishastoyanov.llmprofiles.vendor.webhook;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.mishastoyanov.llmprofiles.LlmProperties;
import io.github.mishastoyanov.llmprofiles.LlmUsage;
import io.github.mishastoyanov.llmprofiles.vendor.LlmVendor;
import io.github.mishastoyanov.llmprofiles.vendor.VendorReply;
import io.github.mishastoyanov.llmprofiles.vendor.VendorRequest;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class WebhookVendor implements LlmVendor {

    /** Enough of a bad answer to recognise it in a log, not so much that it buries the log. */
    private static final int BODY_EXCERPT = 500;

    /** What a profile's settings compile to, kept so each call does not parse them again. */
    private record Compiled(URI uri, RequestTemplate request, ResponsePath text, ResponsePath inputTokens, ResponsePath outputTokens) {
    }

    private final HttpClient http;
    private final URI baseUrl;
    private final LlmProperties.Auth auth;
    private final Duration timeout;
    private final JsonMapper json;
    private final Map<LlmProperties.Profile, Compiled> compiled = new ConcurrentHashMap<>();

    WebhookVendor(HttpClient http, URI baseUrl, LlmProperties.Auth auth, Duration timeout, JsonMapper json) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.auth = auth;
        this.timeout = timeout;
        this.json = json;
    }

    @Override
    public List<String> check(LlmProperties.Profile profile) {
        List<String> problems = new ArrayList<>();
        if (!StringUtils.hasText(profile.path())) {
            problems.add("path is required for a webhook vendor");
        }
        if (!StringUtils.hasText(profile.request())) {
            problems.add("request is required for a webhook vendor, for example '{\"prompt\": {{prompt}}}'");
        }
        if (profile.response() == null || !StringUtils.hasText(profile.response().text())) {
            problems.add("response.text is required for a webhook vendor, for example $.output");
        }
        if (problems.isEmpty()) {
            try {
                Compiled compiled = compile(profile);
                if (profile.reasoning() != null && !compiled.request().uses("reasoning")) {
                    problems.add("reasoning is set, but the request template has no {{reasoning}} to pass it on");
                }
            } catch (IllegalArgumentException e) {
                problems.add(e.getMessage());
            }
        }
        return problems;
    }

    @Override
    public VendorReply call(VendorRequest request) {
        Compiled profile = compiled.computeIfAbsent(request.settings(), this::compile);
        String body = profile.request().render(new RequestTemplate.Values(
                request.text(), request.profile(), request.model(), request.temperature(), request.maxTokens(),
                request.reasoning() == null ? null : request.reasoning().value()));

        HttpRequest.Builder http = HttpRequest.newBuilder(profile.uri())
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (auth != null) {
            http.header(auth.header(), auth.value());
        }

        HttpResponse<String> response = send(http.build());
        if (response.statusCode() / 100 != 2) {
            throw new WebhookCallException("webhook " + profile.uri() + " answered " + response.statusCode()
                    + ": " + excerpt(response.body()));
        }
        return read(profile, response.body());
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new WebhookCallException("webhook " + request.uri() + " could not be called: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WebhookCallException("interrupted while calling webhook " + request.uri(), e);
        }
    }

    /**
     * A whole-body text path also takes a plain-text answer, because "respond with text" is the
     * simplest thing a workflow can be told to do.
     */
    private VendorReply read(Compiled profile, String body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException e) {
            if (profile.text().isWholeBody()) {
                return new VendorReply(body, LlmUsage.UNKNOWN, null);
            }
            throw new WebhookCallException("webhook " + profile.uri() + " did not answer with JSON: " + excerpt(body), e);
        }

        JsonNode text = profile.text().find(root);
        if (text.isMissingNode() || text.isNull()) {
            throw new WebhookCallException("webhook " + profile.uri() + " answer has nothing at "
                    + profile.text() + ": " + excerpt(body));
        }
        Long input = tokens(profile.inputTokens(), root);
        Long output = tokens(profile.outputTokens(), root);
        Long total = input != null && output != null ? input + output : null;
        return new VendorReply(text.isString() ? text.stringValue() : text.toString(), new LlmUsage(input, output, total), null);
    }

    /** Null when the path is not configured, the value is absent, or it is not a whole number. */
    private static Long tokens(ResponsePath path, JsonNode root) {
        if (path == null) {
            return null;
        }
        JsonNode node = path.find(root);
        return node.isIntegralNumber() ? node.longValue() : null;
    }

    private Compiled compile(LlmProperties.Profile profile) {
        LlmProperties.Response response = profile.response();
        return new Compiled(
                resolve(profile.path()),
                RequestTemplate.parse(profile.request(), json),
                ResponsePath.parse(response.text()),
                StringUtils.hasText(response.inputTokens()) ? ResponsePath.parse(response.inputTokens()) : null,
                StringUtils.hasText(response.outputTokens()) ? ResponsePath.parse(response.outputTokens()) : null);
    }

    /** {@code https://n8n.example.com/} and {@code webhook/triage} join with exactly one slash. */
    private URI resolve(String path) {
        String base = baseUrl.toString().replaceAll("/+$", "");
        return URI.create(base + "/" + path.replaceAll("^/+", ""));
    }

    private static String excerpt(String body) {
        if (body == null || body.isEmpty()) {
            return "(empty body)";
        }
        return body.length() <= BODY_EXCERPT ? body : body.substring(0, BODY_EXCERPT) + "…";
    }
}
