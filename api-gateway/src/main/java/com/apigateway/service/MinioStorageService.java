package com.apigateway.service;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.DeleteBucketPolicyArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
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
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Streams multipart uploads to MinIO without buffering the file in heap.
 *
 * The part is spooled to a temp file with backpressure (DataBufferUtils.write),
 * then uploaded from disk on the boundedElastic pool. MinIO and the bucket
 * checks are blocking SDK calls, so they never run on an event loop.
 *
 * Objects are private. Reads go through {@link com.apigateway.controller.StorageDownloadController},
 * which serves them with a fixed, allow-listed content type and
 * `Content-Disposition: attachment` for anything that is not an image, so a
 * crafted upload can never execute script in the application origin.
 */
@Slf4j
@Service
public class MinioStorageService {

    /** Extension → content type used when serving an object. */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp", "gif");
    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of("pdf", "csv");

    private final MinioClient client;
    private final Set<String> knownBuckets = ConcurrentHashMap.newKeySet();

    @Value("${minio.bucket:receipts}")
    private String receiptBucket;

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void makeExistingReceiptsPrivate() {
        try {
            ensureBucket(receiptBucket);
        } catch (Exception e) {
            throw new IllegalStateException("Could not remove the legacy public receipt policy", e);
        }
    }

    public MinioStorageService(
            @Value("${minio.endpoint:http://minio:9000}") String endpoint,
            @Value("${minio.accessKey}") String accessKey,
            @Value("${minio.secretKey}") String secretKey) {
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("MINIO_ACCESS_KEY and MINIO_SECRET_KEY must be configured");
        }
        this.client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    /** Where an uploaded object landed. */
    public record StoredObject(String bucket, String objectName, String url) {}

    public Mono<StoredObject> store(FilePart part, String bucket, String objectPrefix,
                                    Set<String> allowedContentTypes) {
        MediaType contentType = part.headers().getContentType();
        String normalizedType = contentType == null ? "" : contentType.getType().toLowerCase(Locale.ROOT)
                + "/" + contentType.getSubtype().toLowerCase(Locale.ROOT);
        String extension = extensionOf(part.filename());

        boolean allowed = allowedContentTypes.contains(normalizedType)
                // Some browsers send text/plain or application/octet-stream for a
                // CSV statement; accept it only when the name says CSV.
                || ("text/csv".equals(normalizedType) || "application/vnd.ms-excel".equals(normalizedType))
                    && "csv".equals(extension)
                || ("application/octet-stream".equals(normalizedType)
                    && (allowedContentTypes.contains("image/png") || allowedContentTypes.contains("application/pdf"))
                    && (IMAGE_EXTENSIONS.contains(extension) || DOCUMENT_EXTENSIONS.contains(extension)));

        if (!allowed) {
            return Mono.error(new IllegalArgumentException("Unsupported file type: " + normalizedType));
        }

        String objectName = (objectPrefix == null || objectPrefix.isBlank() ? "" : objectPrefix + "/")
                + UUID.randomUUID() + "-" + safeFileName(part.filename());

        return Mono.using(
                () -> {
                    try {
                        return Files.createTempFile("pfa-upload-", ".bin");
                    } catch (Exception e) {
                        throw new IllegalStateException("Could not create a temp file for the upload", e);
                    }
                },
                tmp -> DataBufferUtils.write(part.content(), tmp)
                        .then(Mono.fromCallable(() -> {
                            verifyMagicBytes(tmp, normalizedType, extension);
                            return true;
                        }).subscribeOn(Schedulers.boundedElastic()))
                        .then(upload(tmp, bucket, objectName, normalizedType))
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

    /**
     * Reject a payload whose bytes do not match the declared type. The client
     * controls both the extension and the multipart Content-Type; the bytes are
     * the only thing that can be trusted.
     */
    private void verifyMagicBytes(Path tmp, String contentType, String extension) throws Exception {
        byte[] head = new byte[12];
        try (InputStream in = Files.newInputStream(tmp)) {
            int read = in.readNBytes(head, 0, head.length);
            if (read < 4) throw new IllegalArgumentException("Uploaded file is empty");
        }
        switch (contentType) {
            case "image/png" -> require(head, new int[]{0x89, 'P', 'N', 'G'});
            case "image/jpeg" -> require(head, new int[]{0xFF, 0xD8, 0xFF});
            case "image/gif" -> require(head, new int[]{'G', 'I', 'F', '8'});
            case "image/webp" -> {
                require(head, new int[]{'R', 'I', 'F', 'F'});
                if (head[8] != 'W' || head[9] != 'E' || head[10] != 'B' || head[11] != 'P') {
                    throw new IllegalArgumentException("File is not a valid WebP image");
                }
            }
            case "application/pdf" -> require(head, new int[]{'%', 'P', 'D', 'F'});
            default -> {
                // CSV and octet-stream carries are validated by extension only.
                if (IMAGE_EXTENSIONS.contains(extension) || DOCUMENT_EXTENSIONS.contains(extension)) {
                    // A declared-octet-stream image still has to look like one.
                    if (IMAGE_EXTENSIONS.contains(extension) && !(head[0] == (byte) 0xFF
                            || head[0] == (byte) 0x89 || head[0] == 'G' || head[0] == 'R')) {
                        throw new IllegalArgumentException("File does not match the declared image type");
                    }
                }
            }
        }
    }

    private static void require(byte[] head, int[] expected) {
        for (int i = 0; i < expected.length; i++) {
            if ((head[i] & 0xFF) != (expected[i] & 0xFF)) {
                throw new IllegalArgumentException("File contents do not match the declared type");
            }
        }
    }

    private Mono<Void> upload(Path tmp, String bucket, String objectName, String contentType) {
        return Mono.fromCallable(() -> {
                    ensureBucket(bucket);
                    try (InputStream in = Files.newInputStream(tmp)) {
                        client.putObject(PutObjectArgs.builder()
                                .bucket(bucket)
                                .object(objectName)
                                .stream(in, Files.size(tmp), -1)
                                .contentType(contentType)
                                .build());
                    }
                    return true;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private void ensureBucket(String bucket) throws Exception {
        if (knownBuckets.contains(bucket)) return;
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
        // Older deployments applied a public-read policy to this bucket.
        // Remove it as part of the migration, including on an existing bucket.
        if (receiptBucket.equals(bucket)) {
            client.deleteBucketPolicy(DeleteBucketPolicyArgs.builder().bucket(bucket).build());
        }
        knownBuckets.add(bucket);
    }

    /** Open an object for the download controller. Blocking; call off the event loop. */
    public Mono<GetObjectResponse> open(String bucket, String objectName) {
        return Mono.fromCallable(() -> {
                    ensureBucket(bucket);
                    return client.getObject(GetObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectName)
                            .build());
                })
                .subscribeOn(Schedulers.boundedElastic());
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

    static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isImage(String objectName) {
        return IMAGE_EXTENSIONS.contains(extensionOf(objectName));
    }

    public static boolean isDocument(String objectName) {
        return DOCUMENT_EXTENSIONS.contains(extensionOf(objectName));
    }

    public static String contentTypeOf(String objectName) {
        return switch (extensionOf(objectName)) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "pdf" -> "application/pdf";
            case "csv" -> "text/csv; charset=utf-8";
            default -> "application/octet-stream";
        };
    }
}
