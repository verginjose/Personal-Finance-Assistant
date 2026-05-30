package org.javacode.service;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Orchestrates Groq LLM extraction and live currency conversion.
 *
 * Production improvements over original:
 *  - Config-driven: model, timeout, retries via application.properties
 *  - Retry with exponential backoff on Groq API transient failures
 *  - Exchange-rate cache (1-hour TTL) — avoids hitting Frankfurter on every request
 *  - SLF4J logging throughout (@Slf4j)
 *  - jakarta.annotation.PostConstruct (Jakarta EE 9+, required for Spring Boot 3)
 *  - HttpClient uses a dedicated virtual-thread executor
 */
@Slf4j
@Service
public class FinancialDocumentProcessor {

    private static final String GROQ_URL     = "https://api.groq.com/openai/v1/chat/completions";
    private static final String CURRENCY_URL = "https://api.frankfurter.app/latest";

    // ── Config injected from application.properties ───────────────────────────

    @Value("${groq.api.key}")
    private String groqApiKey;

    @Value("${groq.model:llama-3.3-70b-versatile}")
    private String groqModel;

    @Value("${groq.timeout-seconds:30}")
    private int timeoutSeconds;

    @Value("${groq.max-retries:2}")
    private int maxRetries;

    // ── Infrastructure ────────────────────────────────────────────────────────

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpClient httpClient;

    /**
     * Exchange-rate cache entry: holds the rates map and when it was fetched.
     */
    private record CachedRates(Map<String, Object> data, Instant fetchedAt) {
        boolean isExpired() {
            return Instant.now().isAfter(fetchedAt.plusSeconds(3600)); // 1-hour TTL
        }
    }

    private final ConcurrentHashMap<String, CachedRates> rateCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // Use a virtual-thread executor so HttpClient I/O doesn't pin platform threads
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        log.info("FinancialDocumentProcessor initialized. Model={}, Timeout={}s, MaxRetries={}",
                groqModel, timeoutSeconds, maxRetries);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Full pipeline: sanitize → Groq extract → currency convert.
     */
    public ProcessedFinancialDocument processDocumentAndConvert(DocumentInput data) {
        String cleanText = sanitizeText(data.rawText());
        if (cleanText.length() < 10) {
            throw new IllegalArgumentException("Sanitized text too short (min 10 chars): " + cleanText.length());
        }

        FinancialDocument extracted = extractWithRetry(cleanText, data.userId());

        Map<String, Object> exchangeData = getExchangeRates(extracted.currency());
        CurrencyConversion conversion = calculateCurrencyConversion(
                extracted.amount(), extracted.currency(), exchangeData);

        return new ProcessedFinancialDocument(
                extracted,
                conversion.amountInr(),
                conversion.amountUsd(),
                (String) exchangeData.get("date")
        );
    }

    // ── Groq extraction with retry ────────────────────────────────────────────

    private FinancialDocument extractWithRetry(String text, String userId) {
        int attempt = 0;
        long delayMs = 500;

        while (true) {
            try {
                return extractFinancialDataWithGroq(text, userId);
            } catch (RuntimeException e) {
                attempt++;
                if (attempt > maxRetries) {
                    log.error("Groq extraction failed after {} attempts: {}", attempt, e.getMessage());
                    throw e;
                }
                log.warn("Groq attempt {}/{} failed, retrying in {}ms — {}", attempt, maxRetries, delayMs, e.getMessage());
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry backoff", ie);
                }
                delayMs *= 2; // exponential backoff: 500ms → 1000ms → 2000ms
            }
        }
    }

    /**
     * Single Groq API call — throws RuntimeException on any failure.
     */
    private FinancialDocument extractFinancialDataWithGroq(String text, String userId) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", groqModel);
        body.put("temperature", 0.1);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "user").put("content", buildPrompt(text));

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(GROQ_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + groqApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Groq HTTP request", e);
        }

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Groq API HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText();

            if (content == null || content.isBlank()) {
                throw new RuntimeException("Empty content in Groq response");
            }

            log.debug("Groq response received ({} chars)", content.length());
            return parseFinancialDocument(content, userId);

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("Groq API call failed: " + e.getMessage(), e);
        }
    }

    // ── Exchange rates (cached) ───────────────────────────────────────────────

    public Map<String, Object> getExchangeRates(String baseCurrency) {
        String normalized = normalizeCurrency(baseCurrency);
        String targets    = targetCurrencies(normalized);
        String cacheKey   = normalized + ":" + targets;

        CachedRates cached = rateCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            log.debug("Exchange rate cache hit for {}", cacheKey);
            return cached.data();
        }

        String url = String.format("%s?from=%s&to=%s", CURRENCY_URL, normalized, targets);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url)).GET()
                .timeout(Duration.ofSeconds(5))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = objectMapper.readValue(response.body(), Map.class);
                rateCache.put(cacheKey, new CachedRates(data, Instant.now()));
                log.debug("Exchange rates fetched and cached for {}", cacheKey);
                return data;
            }
            log.warn("Currency API returned {}: {}", response.statusCode(), response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("Failed to fetch exchange rates for {}: {}", cacheKey, e.getMessage());
        }

        // Fallback — return zero rates rather than crashing the whole request
        return Map.of("rates", Map.of("USD", 0.0, "INR", 0.0), "date", LocalDate.now().toString());
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    public String sanitizeText(String text) {
        if (text == null || text.isBlank()) return "";
        return text.replace("\u00A0", " ").replaceAll("\\s+", " ").trim();
    }

    private FinancialDocument parseFinancialDocument(String responseText, String userId)
            throws JsonProcessingException {
        String clean = responseText.strip()
                .replaceFirst("(?s)^```json\\s*", "")
                .replaceFirst("(?s)\\s*```$", "")
                .strip();

        JsonNode node = objectMapper.readTree(clean);
        if (!node.isObject()) {
            throw new IllegalArgumentException("LLM returned non-object JSON: " + clean);
        }
        ((ObjectNode) node).put("userId", userId);
        return objectMapper.treeToValue(node, FinancialDocument.class);
    }

    private String buildPrompt(String text) {
        return """
                You are an expert financial data entry assistant. Analyze the raw text from a financial document.

                **Crucial Instructions:**
                1. Identify the transaction 'type' and return it in a field named "transactionType".
                2. Based on the 'type', you MUST categorize it:
                   - If 'type' is 'Expense', for 'expenseCategory' choose one of: [FOOD_AND_DINING, TRANSPORTATION, SHOPPING, ENTERTAINMENT, BILLS_AND_UTILITIES, HEALTHCARE, TRAVEL, EDUCATION, OTHERS]. 'incomeCategory' must be null.
                   - If 'type' is 'Income', for 'incomeCategory' choose one of: [SALARY, BUSINESS, INVESTMENTS, GIFTS, FREELANCE, RENTAL_INCOME, INTEREST, OTHERS]. 'expenseCategory' must be null.
                3. Identify the 'name' of the vendor or source and return it in a field named "vendor".
                4. Identify the total 'amount' and the 'currency' (use 3-letter ISO code like INR for ₹).
                5. Create a brief 'description' summarizing the transaction.

                **Output Format:**
                Provide the output ONLY in valid JSON format with camelCase keys. Do not include 'userId'. Do not include markdown code blocks.

                ---
                Raw Text to Analyze:
                %s
                ---
                """.formatted(text);
    }

    // ── Currency helpers ──────────────────────────────────────────────────────

    private String normalizeCurrency(String currency) {
        return "₹".equals(currency) ? "INR" : currency.toUpperCase();
    }

    private String targetCurrencies(String base) {
        return switch (base) {
            case "USD" -> "INR";
            case "INR" -> "USD";
            default    -> "USD,INR";
        };
    }

    private CurrencyConversion calculateCurrencyConversion(
            double amount, String currency, Map<String, Object> exchangeData) {

        @SuppressWarnings("unchecked")
        Map<String, Number> rates = (Map<String, Number>) exchangeData.getOrDefault("rates", Map.of());
        double rateUsd = rates.getOrDefault("USD", 0.0).doubleValue();
        double rateInr = rates.getOrDefault("INR", 0.0).doubleValue();

        double usd = switch (currency.toUpperCase()) {
            case "USD" -> amount;
            default    -> amount * rateUsd;
        };
        double inr = switch (currency.toUpperCase()) {
            case "INR" -> amount;
            default    -> amount * rateInr;
        };

        return new CurrencyConversion(round(inr), round(usd));
    }

    private double round(double v) { return Math.round(v * 100.0) / 100.0; }

    // ── Enums and Records ─────────────────────────────────────────────────────

    public enum ExpenseCategory {
        FOOD_AND_DINING, TRANSPORTATION, SHOPPING, ENTERTAINMENT,
        BILLS_AND_UTILITIES, HEALTHCARE, TRAVEL, EDUCATION, OTHERS
    }

    public enum IncomeCategory {
        SALARY, BUSINESS, INVESTMENTS, GIFTS, FREELANCE, RENTAL_INCOME, INTEREST, OTHERS
    }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record FinancialDocument(
            String userId,
            @JsonProperty("vendor") String name,
            double amount,
            @JsonProperty("transactionType") String type,
            ExpenseCategory expenseCategory,
            IncomeCategory incomeCategory,
            String currency,
            String description
    ) {
        @JsonCreator
        public FinancialDocument {
            if ("Expense".equals(type) && expenseCategory == null)
                throw new IllegalArgumentException("expenseCategory required for Expense");
            if ("Income".equals(type) && incomeCategory == null)
                throw new IllegalArgumentException("incomeCategory required for Income");
        }
    }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record ProcessedFinancialDocument(
            FinancialDocument originalData,
            double totalAmountInr,
            double totalAmountUsd,
            String exchangeRateDate
    ) {}

    public record DocumentInput(String userId, String rawText) {}

    private record CurrencyConversion(double amountInr, double amountUsd) {}
}