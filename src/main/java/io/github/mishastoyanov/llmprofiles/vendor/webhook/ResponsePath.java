package io.github.mishastoyanov.llmprofiles.vendor.webhook;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * Where a value sits in a webhook's JSON answer: {@code $}, {@code $.output},
 * {@code $.choices[0].message.content}. Only names and indexes; anything richer belongs to the
 * application, not to a mapping language grown here.
 */
final class ResponsePath {

    private static final Pattern SEGMENT = Pattern.compile("\\.([^.\\[\\]]+)|\\[(\\d+)]");

    private final String text;
    private final List<Object> segments;

    private ResponsePath(String text, List<Object> segments) {
        this.text = text;
        this.segments = segments;
    }

    /** @throws IllegalArgumentException when the path is not of the supported shape */
    static ResponsePath parse(String path) {
        String trimmed = path.strip();
        if (!trimmed.startsWith("$")) {
            throw new IllegalArgumentException("response path '" + path + "' must start with $");
        }
        List<Object> segments = new ArrayList<>();
        Matcher matcher = SEGMENT.matcher(trimmed);
        int position = 1;
        while (position < trimmed.length()) {
            if (!matcher.find(position) || matcher.start() != position) {
                throw new IllegalArgumentException("response path '" + path
                        + "' can only hold .names and [indexes], such as $.choices[0].text");
            }
            segments.add(matcher.group(1) != null ? matcher.group(1) : Integer.valueOf(matcher.group(2)));
            position = matcher.end();
        }
        return new ResponsePath(trimmed, List.copyOf(segments));
    }

    boolean isWholeBody() {
        return segments.isEmpty();
    }

    /** A missing node rather than null when nothing is there. */
    JsonNode find(JsonNode root) {
        JsonNode node = root;
        for (Object segment : segments) {
            node = segment instanceof Integer index ? node.path(index) : node.path((String) segment);
        }
        return node;
    }

    @Override
    public String toString() {
        return text;
    }
}
