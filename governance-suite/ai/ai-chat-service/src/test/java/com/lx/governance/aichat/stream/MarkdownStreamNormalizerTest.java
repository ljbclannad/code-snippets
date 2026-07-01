package com.lx.governance.aichat.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MarkdownStreamNormalizerTest {

    @Test
    void shouldDelayBoldUntilClosingDelimiterArrives() {
        MarkdownStreamNormalizer normalizer = new MarkdownStreamNormalizer();

        assertEquals("3. ", normalizer.onChunk("3. *"));
        assertEquals("", normalizer.onChunk("*戒烟"));
        assertEquals("", normalizer.onChunk("**"));
        assertEquals("**戒烟**", normalizer.finish());
    }

    @Test
    void shouldFlushPlainTextImmediately() {
        MarkdownStreamNormalizer normalizer = new MarkdownStreamNormalizer();

        assertEquals("这是普通文本。", normalizer.onChunk("这是普通文本。"));
        assertEquals("", normalizer.finish());
    }

    @Test
    void shouldAutoCloseInlineCodeAtStreamEnd() {
        MarkdownStreamNormalizer normalizer = new MarkdownStreamNormalizer();

        assertEquals("请执行 ", normalizer.onChunk("请执行 `npm test"));
        assertEquals("`npm test`", normalizer.finish());
    }

    @Test
    void shouldAutoCloseCodeFenceAtStreamEnd() {
        MarkdownStreamNormalizer normalizer = new MarkdownStreamNormalizer();

        assertEquals("", normalizer.onChunk("```java\nSystem.out.println(\"hi\");"));
        assertEquals("```java\nSystem.out.println(\"hi\");\n```", normalizer.finish());
    }

    @Test
    void shouldAutoCloseMarkdownLinkAtStreamEnd() {
        MarkdownStreamNormalizer normalizer = new MarkdownStreamNormalizer();

        assertEquals("", normalizer.onChunk("[文档](https://example.com"));
        assertEquals("[文档](https://example.com)", normalizer.finish());
    }

    @Test
    void shouldAutoCloseBoldWhenSentenceBoundaryArrives() {
        MarkdownStreamNormalizer normalizer = new MarkdownStreamNormalizer();

        assertEquals("**戒烟维持现状并避免被动吸烟。**", normalizer.onChunk("**戒烟维持现状并避免被动吸烟。"));
        assertEquals("\n4. 加强体重管理：", normalizer.onChunk("\n4. 加强体重管理："));
        assertEquals("", normalizer.finish());
    }

    @Test
    void shouldNotAutoCloseBoldOnComma() {
        MarkdownStreamNormalizer normalizer = new MarkdownStreamNormalizer();

        assertEquals("", normalizer.onChunk("**饮食继续优化，"));
        assertEquals("**饮食继续优化，继续执行。**", normalizer.onChunk("继续执行。"));
        assertEquals("", normalizer.finish());
    }
}
