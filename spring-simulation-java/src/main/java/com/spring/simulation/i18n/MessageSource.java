package com.spring.simulation.i18n;

import java.util.Locale;

/**
 * 根据消息编码和 Locale 解析国际化文本。
 */
public interface MessageSource {

    /**
     * 解析消息；找不到编码时返回调用方给出的默认文本。
     */
    String getMessage(String code, Object[] args, String defaultMessage, Locale locale);

    /**
     * 解析消息；找不到编码时抛出异常。
     */
    String getMessage(String code, Object[] args, Locale locale);
}
