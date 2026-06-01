package org.javacode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Objects;
import javax.imageio.ImageIO;
@Slf4j
@Component
public class OcrClient {

    @Value("${ocr.service.url:http://ocr-service:8100}")
    private String ocrUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    public String extractText(byte[] imageBytes, String filename) {
        MediaType mediaType = filename.toLowerCase().endsWith(".png")
                ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;

        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(mediaType);
        fileHeaders.setContentDispositionFormData("file", filename);

        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(imageBytes, fileHeaders));

        ResponseEntity<OcrResponse> response = restTemplate.postForEntity(
                ocrUrl + "/ocr",
                new HttpEntity<>(body, requestHeaders),
                OcrResponse.class
        );

        return Objects.requireNonNull(response.getBody()).getFullText();
    }

    public String extractText(BufferedImage image) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            return extractText(baos.toByteArray(), "scanned-page.png");
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert BufferedImage for OCR", e);
        }
    }
}