package org.javacode.controller;

import net.sourceforge.tess4j.TesseractException;
import org.javacode.dto.CreateEntryResponse;
import org.javacode.service.BillOcrService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/bill")
public class BillOcrController {

    @Autowired
    private BillOcrService billOcrService;

    @PostMapping("/process/{userId}")
    public ResponseEntity<?> processBill(
            @PathVariable String userId,
            @RequestParam(value = "file", required = false) MultipartFile file) {

        if (file == null) {
            return ResponseEntity.badRequest()
                    .body("Missing 'file' parameter. Please ensure your request is multipart/form-data with a 'file' field.");
        }

        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body("Please select a file to upload");
        }

        String contentType = file.getContentType();
        if (contentType == null || (!contentType.startsWith("image/") && !contentType.equals("application/pdf"))) {
            return ResponseEntity.badRequest()
                    .body("Please upload an image file (PNG, JPG) or a PDF file.");
        }

        try {
            CreateEntryResponse result = billOcrService.processFile(userId, file); // Pass userId to service if needed
            return ResponseEntity.ok(result);
        } catch (TesseractException e) {
            return ResponseEntity.badRequest()
                    .body("Error processing OCR: " + e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Unexpected error: " + e.getMessage());
        }
    }
}