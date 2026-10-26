package io.github.mishastoyanov.llmprofiles.internal;

import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.util.Locale;

import io.github.mishastoyanov.llmprofiles.LlmProfile;
import org.springframework.util.ReflectionUtils;

/** How an injection point names its profile, shared by the startup check and by injection. */
final class ProfileNames {

    private ProfileNames() {
    }

    /**
     * {@code localDraft}, {@code local-draft} and {@code local_draft} are one name, the way Spring
     * Boot treats keys in YAML: a Java identifier cannot hold a dash, and a profile name should
     * not have to be written two ways.
     */
    static String canonical(String name) {
        return name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    /**
     * The annotation on a constructor parameter, or else on the field of the same name. The second
     * is for {@code @RequiredArgsConstructor}: Lombok copies a field's annotations to the generated
     * constructor only when told to in {@code lombok.config}, which a library cannot ship.
     */
    static LlmProfile annotationOfConstructorParameter(Parameter parameter, Class<?> declaringClass) {
        LlmProfile own = parameter.getAnnotation(LlmProfile.class);
        if (own != null || !parameter.isNamePresent()) {
            return own;
        }
        Field field = ReflectionUtils.findField(declaringClass, parameter.getName());
        return field == null ? null : field.getAnnotation(LlmProfile.class);
    }

    /** Null when neither an annotation nor a compiled-in name says which profile is meant. */
    static String nameOf(LlmProfile annotation, String implicitName) {
        return annotation != null ? annotation.value() : implicitName;
    }

    static String missingNameAdvice() {
        return "parameter names are not compiled in, so the profile cannot be taken from the name;"
                + " compile with -parameters or name the profile with @LlmProfile";
    }
}
