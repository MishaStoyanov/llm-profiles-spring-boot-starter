package io.github.mishastoyanov.llmprofiles;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names the profile behind an {@link LlmClient} when the field or parameter is called something
 * else. Without it the name decides: {@code LlmClient localDraft} is the profile {@code local-draft}.
 * <p>
 * On a field it also covers the constructor Lombok's {@code @RequiredArgsConstructor} generates for
 * that field.
 */
@Target({ElementType.PARAMETER, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LlmProfile {

    /** Profile name, as written under {@code llm.profiles}. */
    String value();
}
