package com.spring.simulation.aop;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 切点表达式解析器
 *
 * 支持的表达式类型：
 * 1. execution: 匹配方法执行
 *    - execution(* com.example.service.*.*(..))
 *    - execution(public * *(..))
 *    - execution(* save*(..))
 *
 * 2. within: 匹配类型
 *    - within(com.example.service.*)
 *    - within(com.example.service..*)
 *
 * 3. @annotation: 匹配带有指定注解的方法
 *    - @annotation(com.example.Transactional)
 *
 * 4. @within: 匹配带有指定注解的类
 *    - @within(com.example.Service)
 */
public class PointcutExpressionParser {

    /**
     * 判断方法是否匹配切点表达式
     */
    public boolean matches(String expression, Method method, Class<?> targetClass) {
        if (expression == null || expression.isEmpty()) {
            return false;
        }

        expression = expression.trim();

        // 处理 execution 表达式
        if (expression.startsWith("execution(")) {
            return matchesExecution(expression, method, targetClass);
        }

        // 处理 within 表达式
        if (expression.startsWith("within(")) {
            return matchesWithin(expression, targetClass);
        }

        // 处理 @annotation 表达式
        if (expression.startsWith("@annotation(")) {
            return matchesAnnotation(expression, method);
        }

        // 处理 @within 表达式
        if (expression.startsWith("@within(")) {
            return matchesWithinAnnotation(expression, targetClass);
        }

        return false;
    }

    /**
     * 匹配 execution 表达式
     *
     * 格式：execution(modifiers? return-type package.class.method(params))
     * 示例：
     * - execution(* com.example.service.*.*(..))
     * - execution(public * save*(..))
     * - execution(* *Service.*(..))
     */
    private boolean matchesExecution(String expression, Method method, Class<?> targetClass) {
        // 提取括号内的表达式
        String pattern = extractPattern(expression, "execution");
        if (pattern == null) {
            return false;
        }

        // 解析表达式：modifiers? return-type package.class.method(params)
        // 简化实现：只匹配方法名和类名

        // 匹配所有方法：execution(* *(..))
        if (pattern.equals("* *(..)")) {
            return true;
        }

        // 匹配包下所有方法：execution(* com.example.service.*.*(..))
        Pattern pkgPattern = Pattern.compile("\\* ([\\w.]+)\\.\\*\\.\\*\\(\\.\\.\\.?\\)");
        Matcher pkgMatcher = pkgPattern.matcher(pattern);
        if (pkgMatcher.matches()) {
            String packageName = pkgMatcher.group(1);
            return targetClass.getPackage().getName().startsWith(packageName);
        }

        // 匹配特定方法名：execution(* save*(..))
        Pattern methodPattern = Pattern.compile("\\* ([\\w*]+)\\(\\.\\.\\.?\\)");
        Matcher methodMatcher = methodPattern.matcher(pattern);
        if (methodMatcher.matches()) {
            String methodNamePattern = methodMatcher.group(1);
            return matchesMethodName(method.getName(), methodNamePattern);
        }

        // 匹配类和方法：execution(* *Service.*(..))
        Pattern classMethodPattern = Pattern.compile("\\* \\*?([\\w*]+)\\.([\\w*]+)\\(\\.\\.\\.?\\)");
        Matcher classMethodMatcher = classMethodPattern.matcher(pattern);
        if (classMethodMatcher.matches()) {
            String classNamePattern = classMethodMatcher.group(1);
            String methodNamePattern = classMethodMatcher.group(2);
            return matchesClassName(targetClass.getSimpleName(), classNamePattern) &&
                   matchesMethodName(method.getName(), methodNamePattern);
        }

        return false;
    }

    /**
     * 匹配 within 表达式
     *
     * 格式：within(package.class)
     * 示例：
     * - within(com.example.service.*)
     * - within(com.example..*)
     */
    private boolean matchesWithin(String expression, Class<?> targetClass) {
        String pattern = extractPattern(expression, "within");
        if (pattern == null) {
            return false;
        }

        // 匹配包：within(com.example.service.*)
        if (pattern.endsWith(".*")) {
            String packageName = pattern.substring(0, pattern.length() - 2);
            return targetClass.getPackage().getName().equals(packageName);
        }

        // 匹配包及子包：within(com.example..*)
        if (pattern.endsWith("..*")) {
            String packageName = pattern.substring(0, pattern.length() - 3);
            return targetClass.getPackage().getName().startsWith(packageName);
        }

        // 匹配类名模式：within(*Service)
        return matchesClassName(targetClass.getSimpleName(), pattern);
    }

    /**
     * 匹配 @annotation 表达式
     *
     * 格式：@annotation(annotation-type)
     * 示例：@annotation(com.example.Transactional)
     */
    private boolean matchesAnnotation(String expression, Method method) {
        String annotationName = extractPattern(expression, "@annotation");
        if (annotationName == null) {
            return false;
        }

        // 检查方法是否有指定注解
        for (Annotation annotation : method.getAnnotations()) {
            String fullName = annotation.annotationType().getName();
            String simpleName = annotation.annotationType().getSimpleName();
            if (fullName.equals(annotationName) || simpleName.equals(annotationName)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 匹配 @within 表达式
     *
     * 格式：@within(annotation-type)
     * 示例：@within(com.example.Service)
     */
    private boolean matchesWithinAnnotation(String expression, Class<?> targetClass) {
        String annotationName = extractPattern(expression, "@within");
        if (annotationName == null) {
            return false;
        }

        // 检查类是否有指定注解
        for (Annotation annotation : targetClass.getAnnotations()) {
            String fullName = annotation.annotationType().getName();
            String simpleName = annotation.annotationType().getSimpleName();
            if (fullName.equals(annotationName) || simpleName.equals(annotationName)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 提取括号内的模式
     */
    private String extractPattern(String expression, String prefix) {
        if (!expression.startsWith(prefix + "(") || !expression.endsWith(")")) {
            return null;
        }
        return expression.substring(prefix.length() + 1, expression.length() - 1).trim();
    }

    /**
     * 匹配方法名（支持通配符 *）
     */
    private boolean matchesMethodName(String methodName, String pattern) {
        if (pattern.equals("*")) {
            return true;
        }
        if (pattern.contains("*")) {
            String regex = pattern.replace("*", ".*");
            return methodName.matches(regex);
        }
        return methodName.equals(pattern);
    }

    /**
     * 匹配类名（支持通配符 *）
     */
    private boolean matchesClassName(String className, String pattern) {
        if (pattern.equals("*")) {
            return true;
        }
        if (pattern.contains("*")) {
            String regex = pattern.replace("*", ".*");
            return className.matches(regex);
        }
        return className.equals(pattern);
    }
}
