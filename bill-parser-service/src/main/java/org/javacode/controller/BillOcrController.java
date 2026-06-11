package org.javacode.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javacode.dto.CreateEntryResponse;
import org.javacode.service.BillOcrService;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST controller for OCR bill processing.
 *
 * Production improvements:
 *  - Constructor injection via @RequiredArgsConstructor (no field @Autowired)
 *  - Input validation (null, empty, content-type, size) handled here
 *  - No raw exception messages leaked to clients — GlobalExceptionHandler handles errors
 */
@Slf4j
@RestController
@RequestMapping("/bill")
@RequiredArgsConstructor
public class BillOcrController {

    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024; // 10 MB

    private final BillOcrService billOcrService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @PostMapping("/process/{userId}")
    public ResponseEntity<?> processBill(
            @PathVariable String userId,
            @RequestParam(value = "file", required = false) MultipartFile file) {

        // ── Input validation ───────────────────────────────────────────────────
        if (file == null || file.isEmpty()) {
            log.warn("OCR request rejected: missing or empty file for user={}", userId);
            throw new IllegalArgumentException("A non-empty 'file' parameter is required (multipart/form-data).");
        }

        String contentType = file.getContentType();
        if (contentType == null
                || (!contentType.startsWith("image/") && !contentType.equals("application/pdf"))) {
            log.warn("OCR request rejected: unsupported content-type='{}' for user={}", contentType, userId);
            throw new IllegalArgumentException(
                    "Unsupported file type: " + contentType + ". Only image/* and application/pdf are accepted.");
        }

        if (file.getSize() > MAX_FILE_BYTES) {
            log.warn("OCR request rejected: file size {}B exceeds 10MB limit for user={}", file.getSize(), userId);
            throw new IllegalArgumentException("File exceeds the 10 MB size limit.");
        }

        // ── Processing ─────────────────────────────────────────────────────────
        log.info("OCR processing: user={}, filename={}, size={}KB, type={}",
                userId, file.getOriginalFilename(), file.getSize() / 1024, contentType);

        try {
            // Save file to temp location
            Path tempDir = Files.createTempDirectory("ocr-upload-");
            File tempFile = new File(tempDir.toFile(), file.getOriginalFilename() != null ? file.getOriginalFilename() : "bill.pdf");
            file.transferTo(tempFile);

            // Publish job to Kafka
            Map<String, String> job = Map.of(
                    "userId", userId,
                    "filePath", tempFile.getAbsolutePath(),
                    "contentType", contentType,
                    "originalFilename", file.getOriginalFilename() != null ? file.getOriginalFilename() : ""
            );
            kafkaTemplate.send("ocr-jobs", userId, objectMapper.writeValueAsString(job));

            return ResponseEntity.accepted().body(Map.of("status", "processing", "message", "Bill uploaded and is being processed asynchronously."));
        } catch (Exception e) {
            log.error("Failed to enqueue OCR job", e);
            throw new RuntimeException("OCR upload failed: " + e.getMessage(), e);
        }
    }
}