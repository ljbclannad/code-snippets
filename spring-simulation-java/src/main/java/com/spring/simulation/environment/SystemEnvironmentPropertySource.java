package com.spring.simulation.environment;

import java.util.Map;

/**
 * 系统环境变量
 * SystemEnvironmentPropertySource
 */
public class SystemEnvironmentPropertySource implements PropertySource {

    private Map<String, String> systemEnvironmenEnvMap = System.getenv();

    public SystemEnvironmentPropertySource() {
    }

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public String getPropertySource(String name) {
        return systemEnvironmenEnvMap.get(name);
    }

    @Override
    public Map<String, String> getProperties() {
        return systemEnvironmenEnvMap;
    }

    public static void main(String[] args) {
        SystemEnvironmentPropertySource systemEnvironmentPropertySource = new SystemEnvironmentPropertySource();
        System.out.println(systemEnvironmentPropertySource.getProperties());
    }

}
