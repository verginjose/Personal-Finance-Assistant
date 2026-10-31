package com.apigateway.service;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Streams multipart uploads to MinIO without buffering the file in heap.
 *
 * The part is spooled to a temp file with backpressure (DataBufferUtils.write),
 * then uploaded from disk on the boundedElastic pool. MinIO and the bucket
 * checks are blocking SDK calls, so they never run on an event loop.
 */
@Slf4j
@Service
public class MinioStorageService {

    private final MinioClient client;
    private final Set<String> knownBuckets = ConcurrentHashMap.newKeySet();

    public MinioStorageService(
            @Value("${minio.endpoint:http://minio:9000}") String endpoint,
            @Value("${minio.accessKey:admin}") String accessKey,
            @Value("${minio.secretKey:password123}") String secretKey) {
        this.client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    /** Where an uploaded object landed. */
    public record StoredObject(String bucket, String objectName, String url) {}

    public Mono<StoredObject> store(FilePart part, String bucket, String objectPrefix, boolean publicRead) {
        String objectName = (objectPrefix == null || objectPrefix.isBlank() ? "" : objectPrefix + "/")
                + UUID.randomUUID() + "-" + safeFileName(part.filename());
        MediaType contentType = part.headers().getContentType();

        return Mono.using(
                () -> {
                    try {
                        return Files.createTempFile("pfa-upload-", ".bin");
                    } catch (Exception e) {
                        throw new IllegalStateException("Could not create a temp file for the upload", e);
                    }
                },
                tmp -> DataBufferUtils.write(part.content(), tmp)
                        .then(upload(tmp, bucket, objectName, contentType, publicRead))
                        .thenReturn(new StoredObject(bucket, objectName,
                                "/api/storage/" + bucket + "/" + objectName)),
                tmp -> {
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (Exception e) {
                        log.warn("Could not delete temp upload {}: {}", tmp, e.getMessage());
                    }
                }
        ).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> upload(Path tmp, String bucket, String objectName,
                              MediaType contentType, boolean publicRead) {
        return Mono.fromCallable(() -> {
                    ensureBucket(bucket, publicRead);
                    try (InputStream in = Files.newInputStream(tmp)) {
                        client.putObject(PutObjectArgs.builder()
                                .bucket(bucket)
                                .object(objectName)
                                .stream(in, Files.size(tmp), -1)
                                .contentType(contentType != null
                                        ? contentType.toString()
                                        : "application/octet-stream")
                                .build());
                    }
                    return true;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private void ensureBucket(String bucket, boolean publicRead) throws Exception {
        if (knownBuckets.contains(bucket)) return;
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
        if (publicRead) {
            // TODO: replace with presigned URLs / an authenticated read route.
            // The frontend renders receipt images with <img src>, which cannot
            // send an Authorization header, so the objects must stay readable.
            String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Principal\":{\"AWS\":[\"*\"]},\"Action\":[\"s3:GetObject\"],"
                    + "\"Resource\":[\"arn:aws:s3:::" + bucket + "/*\"]}]}";
            client.setBucketPolicy(SetBucketPolicyArgs.builder().bucket(bucket).config(policy).build());
        }
        knownBuckets.add(bucket);
    }

    /** Strip any path components from a client-supplied filename. */
    static String safeFileName(String name) {
        if (name == null || name.isBlank()) return "upload";
        String base = name.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        return base.isBlank() || base.equals(".") || base.equals("..") ? "upload" : base;
    }
}
