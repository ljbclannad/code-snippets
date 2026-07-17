package com.spring.simulation.demo.extension;

import com.spring.simulation.ioc.factory.BeanFactory;

/**
 * @deprecated {@code @Autowired} 已由容器内建的
 *             {@link com.spring.simulation.ioc.postprocessor.AutowiredAnnotationBeanPostProcessor} 处理。
 *             保留此类型仅兼容旧示例代码。
 */
@Deprecated
public class AutowiredAnnotationBeanPostProcessor
        extends com.spring.simulation.ioc.postprocessor.AutowiredAnnotationBeanPostProcessor {

    public AutowiredAnnotationBeanPostProcessor(BeanFactory beanFactory) {
        super(beanFactory);
    }
}
