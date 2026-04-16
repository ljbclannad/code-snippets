package com.spring.simulation.ioc;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class ComponentScanner {
    private String basePackage;

    public ComponentScanner(String basePackage) {
        this.basePackage = basePackage;
    }

    public List<Class<?>> scan() throws Exception {
        System.out.println("  - Scanning package: " + basePackage);
        List<Class<?>> components = new ArrayList<>();

        String path = basePackage.replace('.', '/');
        URL resource = Thread.currentThread().getContextClassLoader().getResource(path);

        if (resource != null) {
            File directory = new File(resource.getFile());
            if (directory.exists()) {
                scanDirectory(directory, basePackage, components);
            }
        }

        System.out.println("  - Found " + components.size() + " components");
        return components;
    }

    private void scanDirectory(File directory, String packageName, List<Class<?>> components) throws Exception {
        File[] files = directory.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(file, packageName + "." + file.getName(), components);
            } else if (file.getName().endsWith(".class")) {
                String className = packageName + "." + file.getName().replace(".class", "");
                Class<?> clazz = Class.forName(className);

                if (clazz.isAnnotationPresent(Component.class)) {
                    components.add(clazz);
                    Component annotation = clazz.getAnnotation(Component.class);
                    String beanName = annotation.value().isEmpty() ?
                            generateBeanName(clazz) : annotation.value();

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
