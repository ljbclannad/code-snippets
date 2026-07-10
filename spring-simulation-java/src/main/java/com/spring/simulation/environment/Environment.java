package com.spring.simulation.environment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 统一的环境配置入口。
 *
 * 职责：
 * 1. 聚合多个 PropertySource
 * 2. 按优先级查找配置
 * 3. 对外屏蔽底层配置来自 OS、YAML 还是其他来源
 */
public class Environment {

    private final List<PropertySource> propertySources = new ArrayList<>();

    public Environment() {
        addPropertySource(PropertySourceFactory.getPropertySource(SystemEnvironmentPropertySource.class));
        addPropertySource(PropertySourceFactory.getPropertySource(YamlPropertySource.class));
    }

    public void addPropertySource(PropertySource propertySource) {
        if (propertySource == null) {
            return;
        }
        propertySources.add(propertySource);
        propertySources.sort(Comparator.comparingInt(PropertySource::getOrder).reversed());
    }

    public String getProperty(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }

        for (int i = 0, size = propertySources.size(); i < size; i++) {
            String value = propertySources.get(i).getPropertySource(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public boolean containsProperty(String key) {
        return getProperty(key) != null;
    }

    /**
     * 保留一个更贴近“环境变量”语义的便捷方法。
     * 当前实现仍然走统一配置查找入口。
     */
    public String getEnv(String key) {
        return getProperty(key);
    }

    public List<PropertySource> getPropertySources() {
        return Collections.unmodifiableList(propertySources);
    }

    @Override
    public String toString() {
        return "Environment[propertySources=" + propertySources.size()
                + ", os=" + System.getProperty("os.name")
                + ", java=" + System.getProperty("java.version") + "]";
    }
}
