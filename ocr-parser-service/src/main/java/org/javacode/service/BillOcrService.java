package org.javacode.service;


import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.javacode.dto.BillProcessingResult;
import org.javacode.dto.CreateEntryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.apache.pdfbox.Loader;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class BillOcrService {

    private final ITesseract tesseract;
    private static final Logger logger = LoggerFactory.getLogger(BillOcrService.class);



    public BillOcrService() {
        tesseract = new Tesseract();
        // Use the correct path for Tesseract 5
        tesseract.setDatapath("/usr/share/tesseract-ocr/5/tessdata");
        tesseract.setLanguage("eng");
        tesseract.setOcrEngineMode(1);
        tesseract.setPageSegMode(1);
        logger.info("Tesseract initialized with path: /usr/share/tesseract-ocr/5/tessdata");
    }
    public CreateEntryResponse processFile(String userId,MultipartFile file) throws TesseractException, IOException {
        String filename = file.getOriginalFilename();
        String extractedText = "";
        CreateEntryResponse createEntryResponse=new CreateEntryResponse();
        FinancialDocumentProcessor processor=new FinancialDocumentProcessor("AIzaSyC2NHJFeIjtFUlYNtFn1SEEsovfWVmQDA8");
        if (filename != null && filename.toLowerCase().endsWith(".pdf")) {
                extractedText = extractTextFromPdf(file);
        }
        else {
                extractedText = extractTextFromImage(file);
        }
        try {
            FinancialDocumentProcessor.ProcessedFinancialDocument result = processor.processDocumentAndConvert(new FinancialDocumentProcessor.DocumentInput(userId,extractedText));

            FinancialDocumentProcessor.FinancialDocument doc = result.originalData();
            createEntryResponse = new CreateEntryResponse(
                    doc.userId(),
                    doc.name(),
                    String.valueOf(doc.amount()),
                    doc.type(),
                    doc.expenseCategory() != null ? doc.expenseCategory().name() : null,
                    doc.incomeCategory() != null ? doc.incomeCategory().name() : null,
                    doc.currency(),
                    doc.description()
            );        }
        catch (Exception e) {
            logger.error(e.getMessage());
        }
        return createEntryResponse;
    }

    private String extractTextFromPdf(MultipartFile file) throws IOException {
        logger.info("Extracting text from pdf");
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            PDFTextStripper pdfStripper = new PDFTextStripper();
            logger.info("Extracted text from pdf {}",file.getOriginalFilename());
            return pdfStripper.getText(document);
        }
    }

    private String extractTextFromImage(MultipartFile file) throws IOException, TesseractException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(file.getBytes()));
        return tesseract.doOCR(image);
    }
}
