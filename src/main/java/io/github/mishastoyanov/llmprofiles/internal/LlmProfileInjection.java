package io.github.mishastoyanov.llmprofiles.internal;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.mishastoyanov.llmprofiles.LlmClient;
import io.github.mishastoyanov.llmprofiles.LlmProfile;
import io.github.mishastoyanov.llmprofiles.LlmProfileName;
import io.github.mishastoyanov.llmprofiles.LlmProfilesConfigurationException;
import io.github.mishastoyanov.llmprofiles.LlmProperties;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * Checks every {@link LlmClient} injection point in the application's beans against
 * {@code llm.profiles}, then installs the resolver that fills them.
 * <p>
 * The check runs before any bean is created and reports every bad name at once. Injection alone
 * would also fail on a bad name, but one at a time, and only for beans that are actually created:
 * a lazy or prototype bean would carry its typo until the first time something asked for it.
 * <p>
 * An injection point is a constructor parameter, a parameter of an {@code @Bean} or
 * {@code @Autowired} method, an {@code @Autowired} field, or anything carrying {@link LlmProfile}.
 * An ordinary method that takes an {@code LlmClient} argument is not one, and is left alone.
 * <p>
 * Constants of {@link LlmProfileName} enums in the application's packages are checked the same way.
 * Every name found is one code depends on, which a refresh is then not allowed to remove.
 */
public final class LlmProfileInjection implements BeanFactoryPostProcessor, EnvironmentAware {

    private Environment environment;
    private final Set<String> namesInCode = new LinkedHashSet<>();

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        Set<String> declared = declaredProfiles();
        Set<String> canonical = new HashSet<>();
        declared.forEach(name -> canonical.add(ProfileNames.canonical(name)));

        List<String> problems = new ArrayList<>();
        Set<Class<?>> scanned = new HashSet<>();
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            for (Class<?> c = typeOf(beanFactory, beanName); c != null && c != Object.class; c = c.getSuperclass()) {
                if (scanned.add(c)) {
                    new Scan(c, canonical, problems, namesInCode).run();
                }
            }
        }
        checkNameEnums(beanFactory, canonical, problems);
        if (!problems.isEmpty()) {
            throw LlmProfilesConfigurationException.of(
                    "Profile names in code do not match llm.profiles (declared: " + declared + "):", problems);
        }
        if (beanFactory instanceof DefaultListableBeanFactory factory) {
            factory.setAutowireCandidateResolver(
                    new LlmProfileAutowireCandidateResolver(factory.getAutowireCandidateResolver(), factory));
        }
    }

    /** The profiles code refers to by name, as written there; known once the bean factory is processed. */
    public Set<String> namesInCode() {
        return Collections.unmodifiableSet(namesInCode);
    }

    /** Enums only: the constants of a class are a fixed list, an implementation's names are not. */
    private void checkNameEnums(ConfigurableListableBeanFactory beanFactory, Set<String> declared, List<String> problems) {
        if (!AutoConfigurationPackages.has(beanFactory)) {
            return;
        }
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false, environment) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isIndependent();
            }
        };
        ClassLoader classLoader = beanFactory.getBeanClassLoader();
        scanner.setResourceLoader(new DefaultResourceLoader(classLoader));
        scanner.addIncludeFilter(new AssignableTypeFilter(LlmProfileName.class));
        Set<String> seen = new HashSet<>();
        for (String basePackage : AutoConfigurationPackages.get(beanFactory)) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
                if (!seen.add(candidate.getBeanClassName())) {
                    continue;
                }
                Class<?> type;
                try {
                    type = ClassUtils.forName(candidate.getBeanClassName(), classLoader);
                } catch (ClassNotFoundException | LinkageError e) {
                    continue;
                }
                if (!type.isEnum()) {
                    continue;
                }
                for (Object constant : type.getEnumConstants()) {
                    String name = ((Enum<?>) constant).name();
                    if (declared.contains(ProfileNames.canonical(name))) {
                        namesInCode.add(name);
                    } else {
                        problems.add(type.getName() + "." + name + ": profile '" + name + "' is not declared");
                    }
                }
            }
        }
    }

    private Set<String> declaredProfiles() {
        return Binder.get(environment).bind("llm", LlmProperties.class)
                .map(properties -> (Set<String>) new LinkedHashSet<>(properties.profiles().keySet()))
                .orElse(Set.of());
    }

    /** Without initialising factory beans: asking a FactoryBean for its type here would build it early. */
    private static Class<?> typeOf(ConfigurableListableBeanFactory beanFactory, String beanName) {
        try {
            Class<?> type = beanFactory.getType(beanName, false);
            return type == null ? null : ClassUtils.getUserClass(type);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private record Scan(Class<?> type, Set<String> declared, List<String> problems, Set<String> found) {

        /** A class whose members cannot all be loaded is skipped: none of them can be ours. */
        void run() {
            try {
                for (Field field : type.getDeclaredFields()) {
                    LlmProfile annotation = field.getAnnotation(LlmProfile.class);
                    if (annotation != null || AnnotatedElementUtils.hasAnnotation(field, Autowired.class)) {
                        check(field.getType(), annotation, field.getName(), type.getName() + "." + field.getName());
                    }
                }
                for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                    for (Parameter parameter : constructor.getParameters()) {
                        check(parameter.getType(), ProfileNames.annotationOfConstructorParameter(parameter, type),
                                implicitName(parameter), type.getName() + "(constructor) parameter '" + parameter.getName() + "'");
                    }
                }
                for (Method method : type.getDeclaredMethods()) {
                    boolean injected = AnnotatedElementUtils.hasAnnotation(method, Bean.class)
                            || AnnotatedElementUtils.hasAnnotation(method, Autowired.class);
                    for (Parameter parameter : method.getParameters()) {
                        LlmProfile annotation = parameter.getAnnotation(LlmProfile.class);
                        if (injected || annotation != null) {
                            check(parameter.getType(), annotation, implicitName(parameter),
                                    type.getName() + "." + method.getName() + "() parameter '" + parameter.getName() + "'");
                        }
                    }
                }
            } catch (LinkageError | TypeNotPresentException e) {
                // skipped, see above
            }
        }

        private void check(Class<?> pointType, LlmProfile annotation, String implicitName, String where) {
            if (pointType != LlmClient.class) {
                if (annotation != null) {
                    problems.add(where + ": @LlmProfile goes on an LlmClient, not on " + pointType.getName());
                }
                return;
            }
            String name = ProfileNames.nameOf(annotation, implicitName);
            if (name == null) {
                problems.add(where + ": " + ProfileNames.missingNameAdvice());
            } else if (!declared.contains(ProfileNames.canonical(name))) {
                problems.add(where + ": profile '" + name + "' is not declared");
            } else {
                found.add(name);
            }
        }

        private static String implicitName(Parameter parameter) {
            return parameter.isNamePresent() ? parameter.getName() : null;
        }
    }
}
