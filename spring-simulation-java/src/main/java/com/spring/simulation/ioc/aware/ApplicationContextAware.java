package com.spring.simulation.ioc.aware;

import com.spring.simulation.ioc.context.ApplicationContext;

public interface ApplicationContextAware {
    void setApplicationContext(ApplicationContext applicationContext);
}
