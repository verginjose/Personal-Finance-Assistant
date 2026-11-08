package com.apigateway.controller;

import com.apigateway.service.MinioStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Serves stored receipts and profile pictures.
 *
 * The storage bucket is private. This route is the only way to read an object
 * and it deliberately hardens the response:
 *
 *   - the content type is derived from an allow-listed extension, never from
 *     the client-supplied upload metadata, and defaults to
 *     `application/octet-stream`;
 *   - non-images are sent as `Content-Disposition: attachment`;
 *   - `X-Content-Type-Options: nosniff` and a `sandbox` CSP stop the browser
 *     from treating an uploaded document as HTML/JS in the app origin;
 *   - only the configured buckets may be read.
 *
 * `<img src>` still works because image responses are inline; a direct
 * navigation to an uploaded HTML/SVG file downloads it instead of executing it.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class StorageDownloadController {

    private final MinioStorageService storage;

    @Value("${minio.bucket:receipts}")
    private String receiptBucket;

    @Value("${minio.profileBucket:profiles}")
    private String profileBucket;

    @GetMapping("/api/storage/{bucket}/{*object}")
    public Mono<Void> download(@PathVariable String bucket,
                               @PathVariable String object,
                               ServerWebExchange exchange) {
        if (!allowedBucket(bucket)) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
        }
        if (object == null || object.isBlank() || object.startsWith("/")
                || object.contains("..") || object.contains("\\")) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST));
        }

        var response = exchange.getResponse();
        String contentType = MinioStorageService.contentTypeOf(object);
        boolean image = MinioStorageService.isImage(object);

        response.getHeaders().setContentType(MediaType.parseMediaType(contentType));
        response.getHeaders().set("X-Content-Type-Options", "nosniff");
        response.getHeaders().set("Content-Security-Policy", "default-src 'none'; sandbox");
        response.getHeaders().set("X-Frame-Options", "DENY");
        response.getHeaders().set("Cache-Control", "private, max-age=300");
        response.getHeaders().set("Content-Disposition",
                (image ? "inline; " : "attachment; ") + "filename=\"" + safeFileName(object) + "\"");

        return storage.open(bucket, object)
                .flatMap(obj -> {
                    String length = obj.headers().get("Content-Length");
                    if (length != null) {
                        try {
                            response.getHeaders().setContentLength(Long.parseLong(length));
                        } catch (NumberFormatException ignored) { /* chunked */ }
                    }
                    Flux<DataBuffer> body = DataBufferUtils.readInputStream(
                            () -> obj, response.bufferFactory(), 8192);
                    return response.writeWith(body.doFinally(signal -> {
                        try { obj.close(); } catch (Exception ignored) { /* best effort */ }
                    }));
                })
                .onErrorResume(e -> {
                    log.debug("Storage object {} not served: {}", object, e.getMessage());
                    if (!response.isCommitted()) {
                        response.setStatusCode(HttpStatus.NOT_FOUND);
                    }
                    return Mono.empty();
                });
    }

    private boolean allowedBucket(String bucket) {
        return receiptBucket.equals(bucket) || profileBucket.equals(bucket);
    }

    private static String safeFileName(String object) {
        String base = object.substring(object.lastIndexOf('/') + 1);
        return base.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
