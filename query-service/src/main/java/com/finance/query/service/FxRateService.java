package com.finance.query.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Currency conversion backed by the ECB reference rates published through
 * Frankfurter (https://frankfurter.dev). No API key is needed.
 *
 * <p>Rates are fetched once per base currency and cached for
 * {@code fx.rates.ttl-minutes}. A failed refresh keeps serving the last good
 * snapshot; when nothing usable is cached the conversion returns {@code null}
 * so the UI can keep showing the original amount as "not converted" rather
 * than a guessed figure. Failures are remembered for
 * {@code fx.rates.failure-backoff-seconds} so an unreachable rates host does
 * not trigger one outbound request per ledger row.
 */
@Slf4j
@Service
public class FxRateService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String ratesUrl;
    private final Duration ttl;
    private final Duration failureBackoff;
    private final Duration requestTimeout;

    private record Snapshot(Map<String, BigDecimal> rates, Instant fetchedAt) {}

    private final ConcurrentHashMap<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> failedAt = new ConcurrentHashMap<>();

    public FxRateService(
            HttpClient httpClient,
            @Value("${fx.rates.url:https://api.frankfurter.dev/v1/latest}") String ratesUrl,
            @Value("${fx.rates.ttl-minutes:360}") long ttlMinutes,
            @Value("${fx.rates.failure-backoff-seconds:60}") long failureBackoffSeconds,
            @Value("${fx.rates.timeout-seconds:5}") long timeoutSeconds) {
        this.httpClient = httpClient;
        this.ratesUrl = ratesUrl == null ? "" : ratesUrl.trim();
        this.ttl = Duration.ofMinutes(Math.max(0, ttlMinutes));
        this.failureBackoff = Duration.ofSeconds(Math.max(0, failureBackoffSeconds));
        this.requestTimeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    /**
     * Converts {@code amount} from {@code from} into {@code to}, rounded to the
     * two decimals the ledger stores. Returns {@code null} when no rate is
     * available, so callers leave the converted field unset.
     */
    public BigDecimal convert(BigDecimal amount, String from, String to) {
        if (amount == null) return null;
        String source = normalize(from);
        String target = normalize(to);
        if (source.isEmpty() || target.isEmpty()) return null;
        if (source.equals(target)) return amount.setScale(2, RoundingMode.HALF_UP);

        BigDecimal rate = rate(source, target);
        if (rate == null) return null;
        try {
            return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        } catch (ArithmeticException e) {
            log.warn("FX conversion overflow for {} {}->{}", amount, source, target);
            return null;
        }
    }

    /** Rate from {@code from} to {@code to}, or {@code null} when unavailable. */
    BigDecimal rate(String from, String to) {
        String base = normalize(from);
        Map<String, BigDecimal> rates = ratesFor(base);
        if (rates == null) return null;
        return rates.get(normalize(to));
    }

    private Map<String, BigDecimal> ratesFor(String base) {
        Instant now = Instant.now();
        Snapshot cached = snapshots.get(base);
        if (cached != null && Duration.between(cached.fetchedAt(), now).compareTo(ttl) < 0) {
            return cached.rates();
        }

        Instant failed = failedAt.get(base);
        if (failed != null && Duration.between(failed, now).compareTo(failureBackoff) < 0) {
            return cached != null ? cached.rates() : null;
        }

        Map<String, BigDecimal> fetched = fetch(base);
        if (fetched != null) {
            snapshots.put(base, new Snapshot(fetched, now));
            failedAt.remove(base);
            return fetched;
        }

        failedAt.put(base, now);
        return cached != null ? cached.rates() : null;
    }

    private Map<String, BigDecimal> fetch(String base) {
        if (ratesUrl.isEmpty()) return null;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ratesUrl + "?from=" + base))
                    .timeout(requestTimeout)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("FX rates lookup failed for {}: HTTP {}", base, response.statusCode());
                return null;
            }

            JsonNode body = MAPPER.readTree(response.body());
            JsonNode ratesNode = body.path("rates");
            if (!ratesNode.isObject()) {
                log.warn("FX rates response for {} contained no rates object", base);
                return null;
            }

            Map<String, BigDecimal> rates = new HashMap<>();
            rates.put(base, BigDecimal.ONE);
            ratesNode.fields().forEachRemaining(entry -> {
                try {
                    rates.put(entry.getKey().toUpperCase(), new BigDecimal(entry.getValue().asText()));
                } catch (NumberFormatException ignored) {
                    // Skip a malformed rate rather than failing the whole table.
                }
            });
            return rates;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("FX rates lookup failed for {}: {}", base, e.toString());
            return null;
        }
    }

    private static String normalize(String currency) {
        return currency == null ? "" : currency.trim().toUpperCase();
    }
}
