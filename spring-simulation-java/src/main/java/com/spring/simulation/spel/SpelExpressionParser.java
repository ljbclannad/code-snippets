package com.spring.simulation.spel;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SpEL 表达式解析器（简化版）
 *
 * Spring Expression Language 支持：
 * 1. 字面量：'hello', 123, true
 * 2. 属性访问：user.name
 * 3. 方法调用：user.getName()
 * 4. 运算符：+, -, *, /, %, ==, !=, <, >, <=, >=, &&, ||, !
 * 5. 三元运算符：condition ? true : false
 * 6. 集合访问：list[0], map['key']
 * 7. 系统属性：systemProperties['user.name']
 * 8. 环境变量：systemEnvironment['PATH']
 *
 * 本实现为简化版，支持基础功能
 */
public class SpelExpressionParser {
    private Map<String, Object> context = new HashMap<>();

    // 系统属性和环境变量
    static {
    }

    public SpelExpressionParser() {
        // 注册系统属性和环境变量
        context.put("systemProperties", System.getProperties());
        context.put("systemEnvironment", System.getenv());
    }

    /**
     * 解析表达式
     *
     * 支持的格式：
     * - ${property.name} - 属性占位符
     * - #{expression} - SpEL 表达式
     * - 字面量 - 直接返回
     */
    public Object parseExpression(String expression) {
        if (expression == null || expression.isEmpty()) {
            return expression;
        }

        // 处理属性占位符 ${...}
        if (expression.startsWith("${") && expression.endsWith("}")) {
            String propertyName = expression.substring(2, expression.length() - 1);
            return resolveProperty(propertyName);
        }

        // 处理 SpEL 表达式 #{...}
        if (expression.startsWith("#{") && expression.endsWith("}")) {
            String spelExpression = expression.substring(2, expression.length() - 1);
            return evaluateSpelExpression(spelExpression);
        }

        // 字面量
        return expression;
    }

    /**
     * 解析属性占位符
     */
    private Object resolveProperty(String propertyName) {
        // 从系统属性获取
        String value = System.getProperty(propertyName);
        if (value != null) {
            return value;
        }

        // 从环境变量获取
        value = System.getenv(propertyName);
        if (value != null) {
            return value;
        }

        // 从上下文获取
        return context.get(propertyName);
    }

    /**
     * 评估 SpEL 表达式
     */
    private Object evaluateSpelExpression(String expression) {
        try {
            // 处理字面量
            if (expression.matches("^\\d+$")) {
                return Integer.parseInt(expression);
            }
            if (expression.matches("^\\d+\\.\\d+$")) {
                return Double.parseDouble(expression);
            }
            if (expression.equals("true") || expression.equals("false")) {
                return Boolean.parseBoolean(expression);
            }
            if (expression.startsWith("'") && expression.endsWith("'")) {
                return expression.substring(1, expression.length() - 1);
            }

            // 处理属性访问：systemProperties['user.name']
            Pattern propertyPattern = Pattern.compile("(\\w+)\\['([^']+)'\\]");
            Matcher propertyMatcher = propertyPattern.matcher(expression);
            if (propertyMatcher.matches()) {
                String objectName = propertyMatcher.group(1);
                String propertyName = propertyMatcher.group(2);
                Object obj = context.get(objectName);
                if (obj instanceof Map) {
                    return ((Map<?, ?>) obj).get(propertyName);
                }
                if (obj instanceof java.util.Properties) {
                    return ((java.util.Properties) obj).getProperty(propertyName);
                }
            }

            // 处理方法调用：object.method()
            Pattern methodPattern = Pattern.compile("(\\w+)\\.(\\w+)\\(\\)");
            Matcher methodMatcher = methodPattern.matcher(expression);
            if (methodMatcher.matches()) {
                String objectName = methodMatcher.group(1);
                String methodName = methodMatcher.group(2);
                Object obj = context.get(objectName);
                if (obj != null) {
                    Method method = obj.getClass().getMethod(methodName);
                    return method.invoke(obj);
                }
            }

            // 处理简单运算：1 + 2
            if (expression.contains("+")) {
                String[] parts = expression.split("\\+");
                if (parts.length == 2) {
                    double left = Double.parseDouble(parts[0].trim());
                    double right = Double.parseDouble(parts[1].trim());
                    return left + right;
                }
            }

            // 处理三元运算符：condition ? true : false
            if (expression.contains("?") && expression.contains(":")) {
                String[] parts = expression.split("\\?");
                String condition = parts[0].trim();
                String[] values = parts[1].split(":");
                boolean conditionResult = Boolean.parseBoolean(condition);
                return conditionResult ? values[0].trim() : values[1].trim();
            }

            // 从上下文获取变量
            return context.get(expression);
        } catch (Exception e) {
            System.err.println("[SpEL] Error evaluating expression: " + expression + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * 设置上下文变量
     */
    public void setVariable(String name, Object value) {
        context.put(name, value);
    }

    /**
     * 获取上下文变量
     */
    public Object getVariable(String name) {
        return context.get(name);
    }
}
