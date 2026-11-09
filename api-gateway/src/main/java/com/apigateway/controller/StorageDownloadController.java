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
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ClientResponse;

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
    private final WebClient storageAccessClient = WebClient.create("http://command-service:8081");

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
        // `{*object}` captures the rest of the path *including* the leading
        // slash (e.g. "/<uuid>-receipt.png). Strip it before treating the value
        // as a MinIO object name.
        String name = object == null ? "" : object.replaceAll("^/+", "");
        if (name.isBlank() || name.contains("..") || name.contains("\\")) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST));
        }

        var response = exchange.getResponse();
        String contentType = MinioStorageService.contentTypeOf(name);
        boolean image = MinioStorageService.isImage(name);

        response.getHeaders().setContentType(MediaType.parseMediaType(contentType));
        response.getHeaders().set("X-Content-Type-Options", "nosniff");
        response.getHeaders().set("Content-Security-Policy", "default-src 'none'; sandbox");
        response.getHeaders().set("X-Frame-Options", "DENY");
        response.getHeaders().set("Cache-Control", "private, max-age=300");
        response.getHeaders().set("Content-Disposition",
                (image ? "inline; " : "attachment; ") + "filename=\"" + safeFileName(name) + "\"");

        Mono<Boolean> authorized;
        if (profileBucket.equals(bucket)) {
            authorized = Mono.just(true);
        } else {
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if (userId == null || userId.isBlank()) {
                return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
            }
            String url = "/api/storage/" + bucket + "/" + name;
            authorized = storageAccessClient.get()
                    .uri(uri -> uri.path("/upsert/storage-access").queryParam("url", url).build())
                    .header("X-User-Id", userId)
                    .exchangeToMono(reply -> Mono.just(reply.statusCode().is2xxSuccessful()))
                    .onErrorReturn(false);
        }

        return authorized.filter(Boolean::booleanValue)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(ignored -> storage.open(bucket, name))
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
                    log.debug("Storage object {} not served: {}", name, e.getMessage());
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
