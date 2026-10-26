package io.github.mishastoyanov.llmprofiles;

import java.util.Set;

public class UnknownProfileException extends IllegalArgumentException {

    public UnknownProfileException(String name, Set<String> declared) {
        super("No LLM profile named '" + name + "'. Declared profiles: " + declared);
    }
}
