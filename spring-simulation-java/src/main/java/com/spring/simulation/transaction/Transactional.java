package com.spring.simulation.transaction;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Transactional {
    String propagation() default "REQUIRED";
    String isolation() default "DEFAULT";
    int timeout() default -1;
    boolean readOnly() default false;
}
