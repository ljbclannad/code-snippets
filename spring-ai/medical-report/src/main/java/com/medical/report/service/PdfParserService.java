package com.medical.report.service;

import com.medical.report.exception.MedicalReportException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
@Service
public class PdfParserService {

    private final HttpClient httpClient;

    public PdfParserService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public String extractTextFromUrl(String pdfUrl) {
        try {
            log.info("Downloading PDF from URL: {}", pdfUrl);
            byte[] pdfBytes = downloadPdf(pdfUrl);

            log.info("Extracting text from PDF, size: {} bytes", pdfBytes.length);
            return extractTextFromBytes(pdfBytes);
        } catch (Exception e) {
            log.error("Failed to extract text from PDF URL: {}", pdfUrl, e);
            throw new MedicalReportException("PDF解析失败: " + e.getMessage(), e);
        }
    }

    private byte[] downloadPdf(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            throw new IOException("Failed to download PDF, status code: " + response.statusCode());
        }

        return response.body();
    }

    private String extractTextFromBytes(byte[] pdfBytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            log.debug("Extracted text length: {} characters", text.length());
            return text;
        }
    }
}
