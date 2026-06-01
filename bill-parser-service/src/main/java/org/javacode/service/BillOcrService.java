package org.javacode.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.javacode.dto.CreateEntryResponse;
import org.javacode.service.FinancialDocumentProcessor.DocumentInput;
import org.javacode.service.FinancialDocumentProcessor.FinancialDocument;
import org.javacode.service.FinancialDocumentProcessor.ProcessedFinancialDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.TimeUnit;





@Slf4j
@Service
public class BillOcrService {

    private static final int MIN_TEXT_LENGTH = 10;

    private final Cache<String, String> ocrCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(24, TimeUnit.HOURS)
            .recordStats()
            .build();

    private final FinancialDocumentProcessor financialDocumentProcessor;
    private final OcrClient ocrClient;

    public BillOcrService(FinancialDocumentProcessor financialDocumentProcessor,
                          OcrClient ocrClient) {
        this.financialDocumentProcessor = financialDocumentProcessor;
        this.ocrClient = ocrClient;
    }

    public CreateEntryResponse processFile(String userId, MultipartFile file)
            throws IOException {

        String filename = file.getOriginalFilename();
        String extractedText = isPdf(filename)
                ? extractTextFromPdf(file)
                : extractTextFromImage(file);

        extractedText = cleanOcrText(extractedText);
        log.info("Extracted {} characters from '{}'", extractedText.length(), filename);

        if (extractedText.isBlank() || extractedText.length() < MIN_TEXT_LENGTH) {
            log.warn("Extracted text too short ({}). Returning empty response.", extractedText.length());
            return new CreateEntryResponse();
        }

        try {
            ProcessedFinancialDocument result = financialDocumentProcessor
                    .processDocumentAndConvert(new DocumentInput(userId, extractedText));

            FinancialDocument doc = result.originalData();
            log.info("Document processed: vendor={}, amount={} {}, type={}",
                    doc.name(), doc.amount(), doc.currency(), doc.type());

            return new CreateEntryResponse(
                    doc.userId(),
                    doc.name(),
                    String.valueOf(doc.amount()),
                    doc.type(),
                    doc.expenseCategory() != null ? doc.expenseCategory().name() : null,
                    doc.incomeCategory()  != null ? doc.incomeCategory().name()  : null,
                    doc.currency(),
                    doc.description()
            );
        } catch (Exception e) {
            log.error("Groq processing failed for user={}: {}", userId, e.getMessage(), e);
            throw new RuntimeException("Failed to parse financial document: " + e.getMessage(), e);
        }
    }

    private boolean isPdf(String filename) {
        return filename != null && filename.toLowerCase().endsWith(".pdf");
    }

    private String extractTextFromPdf(MultipartFile file) throws IOException {
        log.info("we are extracting from PDF");
        try (InputStream is = file.getInputStream();
             PDDocument document = Loader.loadPDF(is.readAllBytes())) {

            String text = new PDFTextStripper().getText(document);
            if (!text.isBlank() && text.length() >= MIN_TEXT_LENGTH) {
                log.debug("PDF text extracted directly ({} chars)", text.length());
                return text;
            }

            log.debug("PDF has no text layer, falling back to OCR");
            BufferedImage image = new PDFRenderer(document).renderImageWithDPI(0, 300);
            return ocrClient.extractText(image); // scanned PDF — BufferedImage path
        }
    }

    private String extractTextFromImage(MultipartFile file) throws IOException {
        byte[] bytes = file.getBytes();
        String hash = DigestUtils.sha256Hex(bytes);

        String cached = ocrCache.getIfPresent(hash);
        if (cached != null) {
            log.debug("OCR cache hit for {}", file.getOriginalFilename());
            return cached;
        }
        String filename = file.getOriginalFilename() != null
                ? file.getOriginalFilename() : "image.jpg";
        String result = ocrClient.extractText(bytes, filename);
        ocrCache.put(hash, result);
        return result;
    }

    private String cleanOcrText(String text) {
        return text
                .replaceAll("[^\\p{Print}\\n]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }
}