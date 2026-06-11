package org.javacode.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javacode.dto.CreateEntryResponse;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OcrJobConsumer {

    private final BillOcrService billOcrService;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @KafkaListener(topics = "ocr-jobs", groupId = "bill-parser-group")
    public void consumeOcrJob(String message) {
        log.info("Received OCR job from Kafka");
        String filePath = null;
        try {
            Map<String, String> job = objectMapper.readValue(message, new TypeReference<Map<String, String>>() {});
            String userId = job.get("userId");
            filePath = job.get("filePath");
            String contentType = job.get("contentType");
            String originalFilename = job.get("originalFilename");

            File file = new File(filePath);
            if (!file.exists()) {
                log.error("File not found for OCR job: {}", filePath);
                return;
            }

            // Read bytes directly
            byte[] fileBytes = Files.readAllBytes(file.toPath());

            log.info("Processing OCR job for user={}", userId);
            CreateEntryResponse response = billOcrService.processFile(userId, fileBytes, originalFilename);
            
            // Publish completion event for SSE notification
            Map<String, Object> completionEvent = Map.of(
                    "userId", userId,
                    "status", "SUCCESS",
                    "data", response,
                    "message", "Bill parsed successfully"
            );
            kafkaTemplate.send("ocr-completed", userId, objectMapper.writeValueAsString(completionEvent));
            log.info("Finished processing OCR job, sent to ocr-completed");

        } catch (Exception e) {
            log.error("Failed to process OCR job asynchronously", e);
            // Optionally, publish failure event
            try {
                Map<String, String> job = objectMapper.readValue(message, new TypeReference<Map<String, String>>() {});
                String userId = job.get("userId");
                Map<String, Object> failureEvent = Map.of(
                        "userId", userId,
                        "status", "ERROR",
                        "message", "Failed to parse bill: " + e.getMessage()
                );
                kafkaTemplate.send("ocr-completed", userId, objectMapper.writeValueAsString(failureEvent));
            } catch (Exception ex) {
                log.error("Failed to send error notification", ex);
            }
        } finally {
            // Clean up temp file
            if (filePath != null) {
                File tempFile = new File(filePath);
                if (tempFile.exists()) {
                    boolean deleted = tempFile.delete();
                    if (!deleted) {
                        log.warn("Failed to delete temp file {}", filePath);
                    }
                }
            }
        }
    }
}
