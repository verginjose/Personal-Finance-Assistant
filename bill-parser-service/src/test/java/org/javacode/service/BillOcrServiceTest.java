package org.javacode.service;

import org.javacode.dto.CreateEntryResponse;
import org.javacode.service.FinancialDocumentProcessor.DocumentInput;
import org.javacode.service.FinancialDocumentProcessor.FinancialDocument;
import org.javacode.service.FinancialDocumentProcessor.ProcessedFinancialDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BillOcrService — Unit Tests")
public class BillOcrServiceTest {

    @Mock
    private FinancialDocumentProcessor financialDocumentProcessor;

    @Mock
    private OcrClient ocrClient;

    @InjectMocks
    private BillOcrService billOcrService;

    @Test
    @DisplayName("processFile: throws exception when image text is too short")
    void processFile_shortText_returnsEmptyResponse() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "receipt.png",
                "image/png",
                "dummy image".getBytes()
        );

        when(ocrClient.extractText(any(byte[].class), anyString())).thenReturn("abc"); // too short

        CreateEntryResponse response = billOcrService.processFile("user-123", file.getBytes(), file.getOriginalFilename());

        assertThat(response.getUserId()).isNull();
        assertThat(response.getName()).isNull();
        verify(financialDocumentProcessor, never()).processDocumentAndConvert(any());
    }

    @Test
    @DisplayName("processFile: successfully processes valid image text")
    void processFile_validImage_returnsExtractedResponse() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "receipt.png",
                "image/png",
                "dummy image".getBytes()
        );

        String ocrText = "Total Amount is $150.00 at Walmart Store";
        when(ocrClient.extractText(any(byte[].class), anyString())).thenReturn(ocrText);

        FinancialDocument doc = new FinancialDocument(
                "user-123",
                "Walmart Store",
                150.00,
                "Expense",
                FinancialDocumentProcessor.ExpenseCategory.SHOPPING,
                null,
                "USD",
                "Ocr parsed receipt",
                null,
                false
        );
        ProcessedFinancialDocument processed = new ProcessedFinancialDocument(doc, 12450.0, 150.0, "2026-06-09");

        when(financialDocumentProcessor.processDocumentAndConvert(any(DocumentInput.class)))
                .thenReturn(processed);

        CreateEntryResponse response = billOcrService.processFile("user-123", file.getBytes(), file.getOriginalFilename());

        assertThat(response.getUserId()).isEqualTo("user-123");
        assertThat(response.getName()).isEqualTo("Walmart Store");
        assertThat(response.getAmount()).isEqualTo("150.0");
        assertThat(response.getExpenseCategory()).isEqualTo("SHOPPING");
        verify(financialDocumentProcessor).processDocumentAndConvert(any(DocumentInput.class));
    }
}
