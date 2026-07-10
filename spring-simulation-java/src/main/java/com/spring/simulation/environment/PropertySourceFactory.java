package com.spring.simulation.environment;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PropertySource 统一获取入口。
 *
 * 用法：
 * PropertySource yaml =
 * PropertySourceFactory.getPropertySource(YamlPropertySource.class);
 * PropertySource env =
 * PropertySourceFactory.getPropertySource(SystemEnvironmentPropertySource.class);
 */
public final class PropertySourceFactory {

    private static final Map<Class<? extends PropertySource>, PropertySource> CACHE = new ConcurrentHashMap<>();

    private PropertySourceFactory() {
    }

    public static <T extends PropertySource> T getPropertySource(Class<T> propertySourceClass) {
        if (propertySourceClass == null) {
            throw new IllegalArgumentException("propertySourceClass must not be null");
        }

        PropertySource propertySource = CACHE.computeIfAbsent(propertySourceClass, PropertySourceFactory::instantiate);
        return propertySourceClass.cast(propertySource);
    }

    private static PropertySource instantiate(Class<? extends PropertySource> propertySourceClass) {
        try {
            Constructor<? extends PropertySource> constructor = propertySourceClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Failed to instantiate PropertySource: " + propertySourceClass.getName()
                            + ". A no-arg constructor is required.",
                    e);
        }
    }

    /**
     * 校验指定 PropertySource 中的必填配置项是否存在且非空。
     *
     * @param propertySourceClass 配置源类型
     * @param requiredKeys        必填 key
     * @throws IllegalStateException 当存在缺失配置时抛出异常
     */
    public static void validateRequiredProperties(
            Class<? extends PropertySource> propertySourceClass,
            String... requiredKeys) {
        if (requiredKeys == null || requiredKeys.length == 0) {
            return;
        }

        PropertySource propertySource = getPropertySource(propertySourceClass);
        List<String> missingKeys = new ArrayList<>();

        for (String requiredKey : requiredKeys) {
            if (requiredKey == null || requiredKey.isBlank()) {
                continue;
            }

            String value = propertySource.getPropertySource(requiredKey);
            if (value == null || value.isBlank()) {
                missingKeys.add(requiredKey);
            }
        }

        if (!missingKeys.isEmpty()) {
            throw new IllegalStateException(
                    "Missing required properties from " + propertySourceClass.getSimpleName()
                            + ": " + missingKeys);
        }
    }

    public static Map<String, String> getProperties(Class<? extends PropertySource> propertySourceClass) {
        return getPropertySource(propertySourceClass).getProperties();
    }

    public static void main(String[] args) {
        PropertySource systemEnvironmentPropertySource = PropertySourceFactory
                .getPropertySource(SystemEnvironmentPropertySource.class);
        System.out.println(systemEnvironmentPropertySource.getProperties());
        validateRequiredProperties(YamlPropertySource.class, "server.port", "server.name");
    }
}
