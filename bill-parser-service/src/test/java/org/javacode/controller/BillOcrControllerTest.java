package org.javacode.controller;

import org.javacode.dto.CreateEntryResponse;
import org.javacode.service.BillOcrService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = BillOcrController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
@DisplayName("BillOcrController — Unit Tests")
public class BillOcrControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BillOcrService billOcrService;

    @MockBean
    private org.springframework.kafka.core.KafkaTemplate<String, String> kafkaTemplate;

    @MockBean
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Test
    @DisplayName("POST /bill/process/{userId}: returns accepted on successful file upload")
    void processBill_succeeds_returnsAccepted() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "receipt.png",
                "image/png",
                "dummy image content".getBytes()
        );

        when(objectMapper.writeValueAsString(any())).thenReturn("{\"dummy\":\"json\"}");

        mockMvc.perform(multipart("/bill/process/user-123")
                        .file(file))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("processing"))
                .andExpect(jsonPath("$.message").exists());
        
        verify(kafkaTemplate).send(eq("ocr-jobs"), eq("user-123"), any());
    }

    @Test
    @DisplayName("POST /bill/process/{userId}: returns 400 when file is missing")
    void processBill_missingFile_returns400() throws Exception {
        mockMvc.perform(multipart("/bill/process/user-123"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /bill/process/{userId}: returns 400 when file content type is unsupported")
    void processBill_invalidContentType_returns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "receipt.txt",
                "text/plain",
                "dummy text content".getBytes()
        );

        mockMvc.perform(multipart("/bill/process/user-123")
                        .file(file))
                .andExpect(status().isBadRequest());
    }
}
