package com.lx.governance.knowledgebase.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.net.URI;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import com.lx.governance.knowledgebase.config.KnowledgeCrawlerProperties;

class MedicalDocumentCrawlServiceTest {

    @Test
    void extractPagePrefersStructuredMedicalContent() throws Exception {
        KnowledgeCrawlerProperties properties = new KnowledgeCrawlerProperties();
        DocumentProcessingService processingService = mock(DocumentProcessingService.class);
        MedicalDocumentCrawlService service = new MedicalDocumentCrawlService(properties, processingService);

        String html = """
                <html>
                  <head>
                    <title>高血压患者家庭血压监测</title>
                    <meta name="description" content="用于指导患者在家中进行血压监测。">
                  </head>
                  <body>
                    <header>导航栏</header>
                    <main>
                      <article>
                        <h1>高血压患者家庭血压监测</h1>
                        <p>建议每天固定时间测量并记录血压。</p>
                        <h2>注意事项</h2>
                        <ul>
                          <li>测量前静坐 5 分钟</li>
                          <li>避免咖啡和剧烈运动后立即测量</li>
                        </ul>
                      </article>
                    </main>
                  </body>
                </html>
                """;

        var extracted = service.extractPage(Jsoup.parse(html, "https://medical.example.com/guide/bp"), new URI("https://medical.example.com/guide/bp"));

        assertTrue(extracted.title().contains("高血压患者家庭血压监测"));
        assertTrue(extracted.summary().contains("血压监测"));
        assertTrue(extracted.content().contains("# 高血压患者家庭血压监测"));
        assertTrue(extracted.content().contains("## 注意事项"));
        assertTrue(extracted.content().contains("- 测量前静坐 5 分钟"));
    }
}
