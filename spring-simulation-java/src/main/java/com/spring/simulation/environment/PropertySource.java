package com.spring.simulation.environment;

import java.util.Map;

public interface PropertySource {

    int getOrder();

    String getPropertySource(String name);

    Map<String, String> getProperties();
}
