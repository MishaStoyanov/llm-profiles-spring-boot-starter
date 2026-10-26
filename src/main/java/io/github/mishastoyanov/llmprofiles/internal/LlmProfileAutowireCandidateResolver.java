package io.github.mishastoyanov.llmprofiles.internal;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;

import io.github.mishastoyanov.llmprofiles.LlmClient;
import io.github.mishastoyanov.llmprofiles.LlmProfile;
import io.github.mishastoyanov.llmprofiles.LlmProfiles;
import io.github.mishastoyanov.llmprofiles.LlmProfilesConfigurationException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.support.AutowireCandidateResolver;
import org.springframework.core.MethodParameter;

/**
 * Answers every {@link LlmClient} injection point from the registry, by its {@link LlmProfile} or
 * else by its name, and hands everything else to the resolver it replaced.
 * <p>
 * Clients are not beans. Registered as beans they would make every plain injection of the type
 * ambiguous, and with a single profile declared Spring would hand that one to any name at all.
 */
final class LlmProfileAutowireCandidateResolver implements AutowireCandidateResolver {

    private final AutowireCandidateResolver delegate;
    private final BeanFactory beanFactory;

    LlmProfileAutowireCandidateResolver(AutowireCandidateResolver delegate, BeanFactory beanFactory) {
        this.delegate = delegate;
        this.beanFactory = beanFactory;
    }

    @Override
    public Object getSuggestedValue(DependencyDescriptor descriptor) {
        if (descriptor.getDependencyType() != LlmClient.class) {
            return delegate.getSuggestedValue(descriptor);
        }
        String name = ProfileNames.nameOf(annotation(descriptor), descriptor.getDependencyName());
        if (name == null) {
            throw new LlmProfilesConfigurationException(descriptor + ": " + ProfileNames.missingNameAdvice());
        }
        return beanFactory.getBean(LlmProfiles.class).profile(name);
    }

    private static LlmProfile annotation(DependencyDescriptor descriptor) {
        MethodParameter methodParameter = descriptor.getMethodParameter();
        if (methodParameter != null && methodParameter.getExecutable() instanceof Constructor<?> constructor) {
            Parameter parameter = constructor.getParameters()[methodParameter.getParameterIndex()];
            return ProfileNames.annotationOfConstructorParameter(parameter, constructor.getDeclaringClass());
        }
        return descriptor.getAnnotation(LlmProfile.class);
    }

    @Override
    public boolean isAutowireCandidate(BeanDefinitionHolder holder, DependencyDescriptor descriptor) {
        return delegate.isAutowireCandidate(holder, descriptor);
    }

    @Override
    public boolean isRequired(DependencyDescriptor descriptor) {
        return delegate.isRequired(descriptor);
    }

    @Override
    public boolean hasQualifier(DependencyDescriptor descriptor) {
        return delegate.hasQualifier(descriptor);
    }

    @Override
    public String getSuggestedName(DependencyDescriptor descriptor) {
        return delegate.getSuggestedName(descriptor);
    }

    @Override
    public Object getLazyResolutionProxyIfNecessary(DependencyDescriptor descriptor, String beanName) {
        return delegate.getLazyResolutionProxyIfNecessary(descriptor, beanName);
    }

    @Override
    public Class<?> getLazyResolutionProxyClass(DependencyDescriptor descriptor, String beanName) {
        return delegate.getLazyResolutionProxyClass(descriptor, beanName);
    }

    @Override
    public AutowireCandidateResolver cloneIfNecessary() {
        return new LlmProfileAutowireCandidateResolver(delegate.cloneIfNecessary(), beanFactory);
    }
}
