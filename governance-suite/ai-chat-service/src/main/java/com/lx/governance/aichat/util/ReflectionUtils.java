package com.lx.governance.aichat.util;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;

/**
 * 反射工具类
 * 用于动态获取对象字段值
 */
@Slf4j
public class ReflectionUtils {

    /**
     * 通过字段路径获取值（支持嵌套路径，如 "healthProfile.bloodSugar"）
     *
     * @param obj       对象
     * @param fieldPath 字段路径
     * @return 字段值，如果不存在返回 null
     */
    public static Object getFieldValue(Object obj, String fieldPath) {
        if (obj == null || fieldPath == null || fieldPath.isEmpty()) {
            return null;
        }

        try {
            String[] parts = fieldPath.split("\\.");
            Object current = obj;

            for (String part : parts) {
                if (current == null) {
                    return null;
                }

                if (current instanceof Map) {
                    // 如果是 Map，直接通过 key 获取
                    current = ((Map<?, ?>) current).get(part);
                } else {
                    // 使用反射获取字段
                    Field field = findField(current.getClass(), part);
                    if (field == null) {
                        log.debug("字段不存在: {} in {}", part, current.getClass().getName());
                        return null;
                    }
                    field.setAccessible(true);
                    current = field.get(current);
                }
            }

            return current;

        } catch (Exception e) {
            log.error("获取字段值失败: fieldPath={}", fieldPath, e);
            return null;
        }
    }

    /**
     * 查找字段（包括父类）
     */
    private static Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 检查值是否为空
     *
     * @param value 值
     * @return true 如果为空
     */
    public static boolean isEmpty(Object value) {
        if (value == null) {
            return true;
        }

        if (value instanceof String) {
            return ((String) value).trim().isEmpty();
        }

        if (value instanceof Collection) {
            return ((Collection<?>) value).isEmpty();
        }

        if (value instanceof Map) {
            return ((Map<?, ?>) value).isEmpty();
        }

        return false;
    }
}
