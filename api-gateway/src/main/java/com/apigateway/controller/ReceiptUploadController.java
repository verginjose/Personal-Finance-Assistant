package com.apigateway.controller;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/receipt")
@RequiredArgsConstructor
public class ReceiptUploadController {

    @Value("${minio.endpoint:http://minio:9000}")
    private String minioEndpoint;

    @Value("${minio.accessKey:admin}")
    private String minioAccessKey;

    @Value("${minio.secretKey:password123}")
    private String minioSecretKey;

    @Value("${minio.bucket:receipts}")
    private String minioBucket;

    private MinioClient getMinioClient() {
        return MinioClient.builder()
                .endpoint(minioEndpoint)
                .credentials(minioAccessKey, minioSecretKey)
                .build();
    }

    @PostMapping(value = "/upload/{userId}", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<Map<String, String>>> uploadReceipt(
            @PathVariable String userId,
            @RequestPart("file") FilePart filePart) {

        String receiptId = UUID.randomUUID().toString();
        String objectName = userId + "/" + receiptId + "-" + filePart.filename();

        return filePart.content()
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    return bytes;
                })
                .reduce((bytes1, bytes2) -> {
                    byte[] combined = new byte[bytes1.length + bytes2.length];
                    System.arraycopy(bytes1, 0, combined, 0, bytes1.length);
                    System.arraycopy(bytes2, 0, combined, bytes1.length, bytes2.length);
                    return combined;
                })
                .publishOn(Schedulers.boundedElastic())
                .map(bytes -> {
                    try {
                        MinioClient minioClient = getMinioClient();
                        
                        // Ensure bucket exists
                        boolean found = minioClient.bucketExists(io.minio.BucketExistsArgs.builder().bucket(minioBucket).build());
                        if (!found) {
                            minioClient.makeBucket(io.minio.MakeBucketArgs.builder().bucket(minioBucket).build());
                            String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":[\"*\"]},\"Action\":[\"s3:GetObject\"],\"Resource\":[\"arn:aws:s3:::" + minioBucket + "/*\"]}]}";
                            minioClient.setBucketPolicy(io.minio.SetBucketPolicyArgs.builder().bucket(minioBucket).config(policy).build());
                        }
                        
                        // Upload to MinIO
                        try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(bytes)) {
                            minioClient.putObject(
                                    PutObjectArgs.builder()
                                            .bucket(minioBucket)
                                            .object(objectName)
                                            .stream(bais, bytes.length, -1)
                                            .contentType("image/jpeg")
                                            .build()
                            );
                        }

                        String fileUrl = "/api/storage/" + minioBucket + "/" + objectName;

                        return ResponseEntity.status(HttpStatus.OK)
                                .body(Map.of("url", fileUrl));

                    } catch (Exception e) {
                        log.error("Failed to upload receipt", e);
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body(Map.of("message", "Internal server error"));
                    }
                });
    }
}
