package org.javacode.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.javacode.dto.CreateEntryResponse;
import org.javacode.service.FinancialDocumentProcessor.DocumentInput;
import org.javacode.service.FinancialDocumentProcessor.FinancialDocument;
import org.javacode.service.FinancialDocumentProcessor.ProcessedFinancialDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
    private final Cache<String, String> ocrCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(24, TimeUnit.HOURS)
            .recordStats() // enables cache hit/miss metrics via logs
            .build();
    /**
     * Thread-local Tesseract instance — one per thread, lazily initialized.
     * Avoids contention and race conditions under concurrent multipart uploads.
     */
    private static final int POOL_SIZE = Runtime.getRuntime().availableProcessors();
    private final BlockingQueue<ITesseract> tesseractPool = new ArrayBlockingQueue<>(POOL_SIZE);

    @PostConstruct
    public void initPool() {
        for (int i = 0; i < POOL_SIZE; i++) {
            Tesseract t = new Tesseract();
            t.setDatapath(TESSERACT_DATA_PATH);
            t.setLanguage("eng");
            t.setOcrEngineMode(1);   // LSTM only — fastest accurate mode
            t.setPageSegMode(6);     // assume single uniform block — faster than PSM 1 for receipts
            t.setVariable("tessedit_do_invert", "0");      // skip inversion check
            t.setVariable("edges_max_children_per_outline", "40"); // limit edge detection
            t.setVariable("textord_heavy_nr", "0");
            t.setVariable("load_system_dawg", "0");        // skip dictionary lookup
            t.setVariable("load_freq_dawg", "0");          // skip frequency dictionary
            tesseractPool.add(t);
        }
    }
    private BufferedImage preprocessImage(BufferedImage original) {
        // Convert to grayscale
        BufferedImage gray = new BufferedImage(
                original.getWidth(), original.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        gray.getGraphics().drawImage(original, 0, 0, null);

        // Scale up small images — Tesseract works best at 300 DPI equivalent
        if (original.getWidth() < 1000) {
            int newW = original.getWidth() * 2;
            int newH = original.getHeight() * 2;
            BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_BYTE_GRAY);
            scaled.getGraphics().drawImage(gray.getScaledInstance(newW, newH, java.awt.Image.SCALE_SMOOTH), 0, 0, null);
            return scaled;
        }
        return gray;
    }

    private final FinancialDocumentProcessor financialDocumentProcessor;

    public BillOcrService(FinancialDocumentProcessor financialDocumentProcessor) {
        this.financialDocumentProcessor = financialDocumentProcessor;
        log.info("BillOcrService initialized. Tesseract data path: {}", TESSERACT_DATA_PATH);
    }

    /**
     * Main entry point: extract text from the file, then run through Groq LLM.
     */
    public CreateEntryResponse processFile(String userId, MultipartFile file)
            throws TesseractException, IOException, InterruptedException {

        String filename = file.getOriginalFilename();
        String extractedText = isPdf(filename)
                ? extractTextFromPdf(file)
                : extractTextFromImage(file);
        extractedText = cleanOcrText(extractedText); // add this line
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

    private String extractTextFromPdf(MultipartFile file) throws IOException, TesseractException, InterruptedException {
        try (InputStream is = file.getInputStream();
             PDDocument document = Loader.loadPDF(is.readAllBytes())) {
            String text = new PDFTextStripper().getText(document);
            if (!text.isBlank() && text.length() >= MIN_TEXT_LENGTH) {
                log.debug("PDF text extracted directly ({} chars)", text.length());
                return text;
            }
            // Scanned PDF — fall back to OCR on first page
            log.debug("PDF has no text layer, falling back to OCR");
            BufferedImage image = new org.apache.pdfbox.rendering.PDFRenderer(document)
                    .renderImageWithDPI(0, 300);
            return extractTextFromImageDirectly(image);
        }
    }
    private String extractTextFromImage(MultipartFile file)
            throws IOException, InterruptedException {

        byte[] bytes = file.getBytes();
        String hash = DigestUtils.sha256Hex(bytes);

        String cached = ocrCache.getIfPresent(hash);
        if (cached != null) {
            log.debug("OCR cache hit for {}", file.getOriginalFilename());
            return cached;
        }

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null)
            throw new IllegalArgumentException("Could not decode image: " + file.getOriginalFilename());

        BufferedImage processed = preprocessImage(image);

        ITesseract tess = tesseractPool.take(); // take BEFORE supplyAsync
        String result;
        try {
            result = CompletableFuture
                    .supplyAsync(() -> {
                        try { return tess.doOCR(processed); }
                        catch (TesseractException e) { throw new RuntimeException(e); }
                    })
                    .orTimeout(30, TimeUnit.SECONDS)
                    .join();
        } finally {
            tesseractPool.offer(tess); // always return, even on timeout
        }

        ocrCache.put(hash, result);
        return result;
    }

    // Keep this for the PDF scanned fallback only
    private String extractTextFromImageDirectly(BufferedImage image)
            throws TesseractException, InterruptedException {
        BufferedImage processed = preprocessImage(image);
        ITesseract tess = tesseractPool.take();
        try {
            return tess.doOCR(processed);
        } finally {
            tesseractPool.offer(tess);
        }
    }

    private String cleanOcrText(String text) {
        return text
                .replaceAll("[^\\p{Print}\\n]", "") // remove non-printable chars
                .replaceAll("\\s+", " ")
                .trim();
    }
}
