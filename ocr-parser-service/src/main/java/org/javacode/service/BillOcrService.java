package org.javacode.service;

import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.javacode.dto.CreateEntryResponse;
import org.javacode.service.FinancialDocumentProcessor.DocumentInput;
import org.javacode.service.FinancialDocumentProcessor.FinancialDocument;
import org.javacode.service.FinancialDocumentProcessor.ProcessedFinancialDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Orchestrates OCR extraction (Tesseract for images, PDFBox for PDFs) and
 * hands the extracted text off to FinancialDocumentProcessor (Groq LLM).
 *
 * Thread-safety note:
 *   Tesseract4j's ITesseract is NOT thread-safe. Using ThreadLocal ensures
 *   each virtual/platform thread gets its own instance, avoiding data races
 *   under concurrent requests without explicit synchronization overhead.
 */
@Slf4j
@Service
public class BillOcrService {

    private static final String TESSERACT_DATA_PATH = "/usr/share/tesseract-ocr/5/tessdata";
    private static final int    MIN_TEXT_LENGTH      = 10;

    /**
     * Thread-local Tesseract instance — one per thread, lazily initialized.
     * Avoids contention and race conditions under concurrent multipart uploads.
     */
    private final ThreadLocal<ITesseract> tesseractThreadLocal = ThreadLocal.withInitial(() -> {
        Tesseract t = new Tesseract();
        t.setDatapath(TESSERACT_DATA_PATH);
        t.setLanguage("eng");
        t.setOcrEngineMode(1);   // LSTM mode
        t.setPageSegMode(1);     // Automatic page segmentation with OSD
        log.debug("Tesseract instance created for thread: {}", Thread.currentThread().getName());
        return t;
    });

    private final FinancialDocumentProcessor financialDocumentProcessor;

    public BillOcrService(FinancialDocumentProcessor financialDocumentProcessor) {
        this.financialDocumentProcessor = financialDocumentProcessor;
        log.info("BillOcrService initialized. Tesseract data path: {}", TESSERACT_DATA_PATH);
    }

    /**
     * Main entry point: extract text from the file, then run through Groq LLM.
     */
    public CreateEntryResponse processFile(String userId, MultipartFile file)
            throws TesseractException, IOException {

        String filename = file.getOriginalFilename();
        String extractedText = isPdf(filename)
                ? extractTextFromPdf(file)
                : extractTextFromImage(file);

        log.info("Extracted {} characters from '{}'", extractedText.length(), filename);

        if (extractedText.isBlank() || extractedText.length() < MIN_TEXT_LENGTH) {
            log.warn("Extracted text is too short ({}). Returning empty response.", extractedText.length());
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
            log.error("Groq document processing failed for user={}: {}", userId, e.getMessage(), e);
            throw new RuntimeException("Failed to parse financial document via LLM: " + e.getMessage(), e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private boolean isPdf(String filename) {
        return filename != null && filename.toLowerCase().endsWith(".pdf");
    }

    private String extractTextFromPdf(MultipartFile file) throws IOException {
        log.debug("Extracting text from PDF: {}", file.getOriginalFilename());
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            return new PDFTextStripper().getText(document);
        }
    }

    private String extractTextFromImage(MultipartFile file) throws IOException, TesseractException {
        log.debug("Extracting text from image: {}", file.getOriginalFilename());
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(file.getBytes()));
        if (image == null) {
            throw new IllegalArgumentException("Could not decode image file: " + file.getOriginalFilename());
        }
        // Use the thread-local Tesseract instance — safe for virtual threads
        return tesseractThreadLocal.get().doOCR(image);
    }
}
