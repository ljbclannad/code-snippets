package com.lx.governance.knowledgebase.parser;

import com.lx.governance.knowledgebase.domain.ParsedDocument;

import java.io.File;
import java.io.IOException;

/**
 * 文档解析器接口
 */
public interface DocumentParser {

    /**
     * 解析文档
     *
     * @param file 文件
     * @param docId 文档ID
     * @return 解析后的文档
     * @throws IOException 解析异常
     */
    ParsedDocument parse(File file, String docId) throws IOException;

    /**
     * 是否支持该文件类型
     *
     * @param fileType 文件类型
     * @return 是否支持
     */
    boolean supports(String fileType);
}
