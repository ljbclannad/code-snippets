package com.spring.simulation.ioc.scanner;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import com.spring.simulation.ioc.annotation.Component;
import com.spring.simulation.ioc.annotation.Configuration;
import com.spring.simulation.ioc.annotation.Scope;
import com.spring.simulation.ioc.scope.BeanScope;

public class ComponentScanner {
    private String[] basePackages;

    public ComponentScanner(String... basePackage) {
        this.basePackages = basePackage;
    }

    public List<Class<?>> scan() throws Exception {
        List<Class<?>> components = new ArrayList<>();
        for (String basePackage : basePackages) {
            System.out.println("  - Scanning package: " + basePackage);
            String path = basePackage.replace('.', '/');
            URL resource = Thread.currentThread().getContextClassLoader().getResource(path);

            if (resource != null) {
                File directory = new File(resource.getFile());
                if (directory.exists()) {
                    scanDirectory(directory, basePackage, components);
                }
            }
            System.out.println("  - Found " + components.size() + " components");

        }
        return components;
    }

    private void scanDirectory(File directory, String packageName, List<Class<?>> components) throws Exception {
        File[] files = directory.listFiles();
        if (files == null)
            return;

        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(file, packageName + "." + file.getName(), components);
            } else if (file.getName().endsWith(".class")) {
                String className = packageName + "." + file.getName().replace(".class", "");
                Class<?> clazz = Class.forName(className);

                // 遍历注解，如果存在component或者configuration的情况下，标记为可识别的bean
                if (clazz.isAnnotationPresent(Component.class) || clazz.isAnnotationPresent(Configuration.class)) {
                    components.add(clazz);
                    Component annotation = clazz.getAnnotation(Component.class);
                    String beanName = annotation == null || annotation.value().isEmpty() ? generateBeanName(clazz) : annotation.value();

                    // 检查 @Scope 注解
                    String scope = BeanScope.SINGLETON;
                    if (clazz.isAnnotationPresent(Scope.class)) {
                        scope = clazz.getAnnotation(Scope.class).value();
                    }

                    System.out.println("    - Found @Component: " + clazz.getSimpleName() +
                            " -> " + beanName + " (scope: " + scope + ")");
                }
            }
        }
    }

    private String generateBeanName(Class<?> clazz) {
        String simpleName = clazz.getSimpleName();
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }
}
