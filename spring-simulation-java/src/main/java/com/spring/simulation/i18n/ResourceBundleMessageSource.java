package com.spring.simulation.i18n;

import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.MissingResourceException;

/**
 * 基于 JDK {@link ResourceBundle} 的轻量消息源实现。
 *
 * <p>例如 basename 为 {@code messages} 时，会按 Locale 自动查找
 * {@code messages.properties}、{@code messages_zh_CN.properties} 等资源文件。</p>
 */
public class ResourceBundleMessageSource implements MessageSource {
    private String[] basenames = new String[0];

    public void setBasenames(String... basenames) {
        if (basenames == null || basenames.length == 0) {
            throw new IllegalArgumentException("At least one message basename is required");
        }
        this.basenames = Arrays.stream(basenames)
                .filter(basename -> basename != null && !basename.trim().isEmpty())
                .map(String::trim)
                .toArray(String[]::new);
        if (this.basenames.length == 0) {
            throw new IllegalArgumentException("At least one non-empty message basename is required");
        }
    }

    @Override
    public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
        try {
            return getMessage(code, args, locale);
        } catch (IllegalArgumentException ex) {
            return defaultMessage;
        }
    }

    @Override
    public String getMessage(String code, Object[] args, Locale locale) {
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalArgumentException("Message code must not be blank");
        }

        Locale targetLocale = locale == null ? Locale.getDefault() : locale;
        for (String basename : basenames) {
            try {
                // 不回退到 JVM 默认 Locale，避免请求 en_US 却意外解析到 zh_CN。
                ResourceBundle bundle = ResourceBundle.getBundle(
                        basename,
                        targetLocale,
                        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_DEFAULT));
                if (bundle.containsKey(code)) {
                    String pattern = bundle.getString(code);
                    return args == null || args.length == 0
                            ? pattern
                            : new MessageFormat(pattern, targetLocale).format(args);
                }
            } catch (MissingResourceException ignored) {
                // 当前 basename 没有对应资源，继续尝试下一个。
            }
        }
        throw new IllegalArgumentException("No message found for code '" + code + "' and locale " + targetLocale);
    }
}
