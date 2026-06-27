package io.github.messageguard.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface GuardedConsumer {

    String consumer();

    String group();

    String messageAdapter() default "rabbitmq";

    String idempotentKey() default "";

    int maxRetries() default 3;

    Class<? extends Throwable>[] retryOn() default {};

    Class<? extends Throwable>[] noRetryOn() default {};

    String compensationHandler() default "";

    AlertLevel alertLevel() default AlertLevel.MEDIUM;
}
