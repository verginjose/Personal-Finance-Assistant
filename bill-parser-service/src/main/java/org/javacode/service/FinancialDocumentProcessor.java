package org.javacode.service;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates Groq LLM extraction and live currency conversion.
 *
 * Production improvements over original:
 *  - Config-driven: model, timeout, retries via application.yaml
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
    private static final String currencyUrl = "https://api.frankfurter.dev/v1/latest?from=USD";
    // ── Config injected from application.yaml ───────────────────────────

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

    // Replace the per-key ConcurrentHashMap cache with a single volatile map
    private volatile Map<String, Double> allRates = Map.of();
    private volatile String rateDate = "";
    private final Cache<String, FinancialDocument> llmCache =
            Caffeine.newBuilder()
                    .maximumSize(200)
                    .expireAfterWrite(24, TimeUnit.HOURS)
                    .build();
    @PostConstruct
    public void init() {
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        fetchAllRates(); // eager load on startup
        log.info("FinancialDocumentProcessor initialized. Model={}, Timeout={}s, MaxRetries={}",
                groqModel, timeoutSeconds, maxRetries);
    }

    // Runs every 24 hours after startup
    @Scheduled(cron = "0 30 18 * * *", zone = "UTC")
    public void fetchAllRates() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(currencyUrl)).GET()
                .timeout(Duration.ofSeconds(5))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                Map<String, Double> rates = new ConcurrentHashMap<>();
                root.path("rates").fields().forEachRemaining(e ->
                        rates.put(e.getKey(), e.getValue().asDouble()));
                rates.put("USD", 1.0); // base currency
                allRates = Map.copyOf(rates); // immutable snapshot, thread-safe;
                rateDate = root.path("date").asText();
                log.info("Exchange rates refreshed for {} currencies, date={}", rates.size(), rateDate);
            } else {
                log.warn("Exchange rate fetch failed: HTTP {}", response.statusCode());
            }
        } catch (Exception e) {
            log.error("Exchange rate fetch error: {}", e.getMessage());
        }
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

        CurrencyConversion conversion = calculateCurrencyConversion(
                extracted.amount(), extracted.currency());

        return new ProcessedFinancialDocument(
                extracted,
                conversion.amountInr(),
                conversion.amountUsd(),
                rateDate
        );
    }

    // ── Groq extraction with retry ────────────────────────────────────────────

    private FinancialDocument extractWithRetry(String text, String userId) {
        String hash = DigestUtils.sha256Hex(text);

        FinancialDocument cached = llmCache.getIfPresent(hash);
        if (cached != null) {
            log.debug("LLM cache hit, skipping Groq");
            // userId may differ — return with correct userId
            return new FinancialDocument(
                    userId,
                    cached.name(),
                    cached.amount(),
                    cached.type(),
                    cached.expenseCategory(),
                    cached.incomeCategory(),
                    cached.currency(),
                    cached.description()
            );
        }
        int attempt = 0;
        long delayMs = 500;

        while (true) {
            try {
                FinancialDocument result=extractFinancialDataWithGroq(text, userId);
                llmCache.put(hash,result);
                return result;
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
        
        ObjectNode responseFormat = objectMapper.createObjectNode();
        responseFormat.put("type", "json_object");
        body.set("response_format", responseFormat);

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

            GroqResponse groq = objectMapper.readValue(response.body(), GroqResponse.class);
            String content = groq.choices().getFirst().message().content();

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
            Extract financial data from this receipt/document. Return ONLY valid JSON.
            
            Rules:
            - transactionType: "Expense" or "Income"
            - expenseCategory (if Expense): FOOD_AND_DINING|TRANSPORTATION|SHOPPING|ENTERTAINMENT|BILLS_AND_UTILITIES|HEALTHCARE|TRAVEL|EDUCATION|OTHERS
            - incomeCategory (if Income): SALARY|BUSINESS|INVESTMENTS|GIFTS|FREELANCE|RENTAL_INCOME|INTEREST|OTHERS
            - Set the unused category to null
            - currency: 3-letter ISO code (INR for ₹)
            
            JSON schema:
            {"vendor":"string","amount":0.0,"transactionType":"string","expenseCategory":"string|null","incomeCategory":"string|null","currency":"string","description":"string"}
            
            Receipt:
            %s
            """.formatted(text);
    }
    // ── Currency helpers ──────────────────────────────────────────────────────

    private String normalizeCurrency(String currency) {
        return "₹".equals(currency) ? "INR" : currency.toUpperCase();
    }

    private CurrencyConversion calculateCurrencyConversion(double amount, String currency) {
        String normalized = normalizeCurrency(currency);

        // Convert everything to USD first, then to INR
        double inUsd = normalized.equals("USD")
                ? amount
                : amount / allRates.getOrDefault(normalized, 1.0);

        double inInr = inUsd * allRates.getOrDefault("INR", 83.0); // 83 fallback

        return new CurrencyConversion(round(inInr), round(inUsd));
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GroqResponse(List<GroqChoice> choices) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GroqChoice(GroqMessage message) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GroqMessage(String content) {}

}