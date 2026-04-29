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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

@Service
public class FinancialDocumentProcessor {

    private static final Logger logger = Logger.getLogger(FinancialDocumentProcessor.class.getName());
    private static final String GROQ_MODEL = "llama-3.3-70b-versatile";
    private static final String GROQ_CHAT_COMPLETIONS_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String CURRENCY_API_URL = "https://api.frankfurter.app/latest";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${GROQ_API_KEY}")
    private String groqApiKey;

    public FinancialDocumentProcessor() {
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void initializeLlmClient() {
        logger.info("Groq initialized for REST API usage");
    }

    // --- Enums and Records ---

    public enum ExpenseCategory {
        FOOD_AND_DINING,
        TRANSPORTATION,
        SHOPPING,
        ENTERTAINMENT,
        BILLS_AND_UTILITIES,
        HEALTHCARE,
        TRAVEL,
        EDUCATION,
        OTHERS
    }

    public enum IncomeCategory {
        SALARY,
        BUSINESS,
        INVESTMENTS,
        GIFTS,
        FREELANCE,
        RENTAL_INCOME,
        INTEREST,
        OTHERS
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
            validateTransaction(type, expenseCategory, incomeCategory);
        }

        private static void validateTransaction(String type, ExpenseCategory expenseCategory, IncomeCategory incomeCategory) {
            if ("Expense".equals(type)) {
                if (expenseCategory == null) {
                    throw new IllegalArgumentException("expenseCategory must be set for Expense transactions");
                }
                if (incomeCategory != null) {
                    throw new IllegalArgumentException("incomeCategory must not be set for Expense transactions");
                }
            } else if ("Income".equals(type)) {
                if (incomeCategory == null) {
                    throw new IllegalArgumentException("incomeCategory must be set for Income transactions");
                }
                if (expenseCategory != null) {
                    throw new IllegalArgumentException("expenseCategory must not be set for Income transactions");
                }
            }
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

    /**
     * Sanitizes input text by removing problematic characters and normalizing whitespace.
     */
    public String sanitizeText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replace("\u00A0", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Fetches exchange rates from Frankfurter API.
     */
    public Map<String, Object> getExchangeRates(String baseCurrency) {
        String normalizedCurrency = normalizeCurrency(baseCurrency);
        String targets = determineTargetCurrencies(normalizedCurrency);
        String apiUrl = String.format("%s?from=%s&to=%s", CURRENCY_API_URL, normalizedCurrency, targets);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
                return result;
            } else {
                logger.log(Level.WARNING, "Currency API request failed with status: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            logger.log(Level.SEVERE, "Error calling currency API", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }

        // Return default values on error
        return Map.of(
                "rates", Map.of("USD", 0.0, "INR", 0.0),
                "date", LocalDate.now().toString()
        );
    }

    private String normalizeCurrency(String currency) {
        if ("₹".equals(currency)) {
            return "INR";
        }
        return currency.toUpperCase();
    }

    private String determineTargetCurrencies(String baseCurrency) {
        return switch (baseCurrency) {
            case "USD" -> "INR";
            case "INR" -> "USD";
            default -> "USD,INR";
        };
    }

    /**
     * Extracts financial data using Groq Chat Completions API.
     */
    public FinancialDocument extractFinancialDataWithGroq(String text, String userId) {
        String prompt = buildExtractionPrompt(text);
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", GROQ_MODEL);
        requestBody.put("temperature", 0.1);

        ArrayNode messages = requestBody.putArray("messages");
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GROQ_CHAT_COMPLETIONS_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + groqApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .timeout(Duration.ofSeconds(30))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                logger.log(Level.SEVERE, "Groq API returned status {0}: {1}", new Object[]{response.statusCode(), response.body()});
                throw new IOException("Groq API error: " + response.statusCode() + " Details: " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            String responseText = root.path("choices")
                    .path(0)
                    .path("message")
                    .path("content")
                    .asText();

            if (responseText == null || responseText.isBlank()) {
                throw new IOException("Empty response text from Groq API");
            }

            return parseFinancialDocument(responseText, userId);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to extract financial data with Groq API", e);
            throw new RuntimeException("Failed to extract financial data with Groq API", e);
        }
    }


    private String buildExtractionPrompt(String text) {
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

    private FinancialDocument parseFinancialDocument(String responseText, String userId) throws JsonProcessingException {

        // Clean JSON response (remove Markdown code blocks if present)
        String cleanJson = responseText.strip()
                .replaceFirst("(?s)^```json\\s*", "")
                .replaceFirst("(?s)\\s*```$", "")
                .strip();

        JsonNode dataNode = objectMapper.readTree(cleanJson);

        if (!dataNode.isObject()) {
            throw new IllegalArgumentException("LLM returned non-object JSON: " + cleanJson);
        }

        // Add userId to the JSON
        ObjectNode objectDataNode = (ObjectNode) dataNode;
        objectDataNode.put("userId", userId);

        return objectMapper.treeToValue(objectDataNode, FinancialDocument.class);
    }

    /**
     * Main processing function orchestrating the full workflow.
     */
    public ProcessedFinancialDocument processDocumentAndConvert(DocumentInput data) {
        // Validate and sanitize input
        String cleanText = sanitizeText(data.rawText());
        if (cleanText.length() < 10) {
            throw new IllegalArgumentException("Sanitized text is too short to process (minimum 10 characters)");
        }

        // Extract financial data using Groq
        FinancialDocument extractedData = extractFinancialDataWithGroq(cleanText, data.userId());

        // Get exchange rates
        Map<String, Object> exchangeData = getExchangeRates(extractedData.currency());

        // Calculate amounts in USD and INR
        CurrencyConversion conversion = calculateCurrencyConversion(
                extractedData.amount(),
                extractedData.currency(),
                exchangeData
        );

        return new ProcessedFinancialDocument(
                extractedData,
                conversion.amountInr(),
                conversion.amountUsd(),
                (String) exchangeData.get("date")
        );
    }

    private record CurrencyConversion(double amountInr, double amountUsd) {}

    private CurrencyConversion calculateCurrencyConversion(
            double originalAmount,
            String currency,
            Map<String, Object> exchangeData
    ) {
        @SuppressWarnings("unchecked")
        Map<String, Number> rates = (Map<String, Number>) exchangeData.getOrDefault("rates", Map.of());

        double rateUsd = rates.getOrDefault("USD", 0.0).doubleValue();
        double rateInr = rates.getOrDefault("INR", 0.0).doubleValue();

        double amountUsd, amountInr;

        if ("USD".equalsIgnoreCase(currency)) {
            amountUsd = originalAmount;
            amountInr = originalAmount * rateInr;
        } else if ("INR".equalsIgnoreCase(currency)) {
            amountInr = originalAmount;
            amountUsd = originalAmount * rateUsd;
        } else {
            amountUsd = originalAmount * rateUsd;
            amountInr = originalAmount * rateInr;
        }

        return new CurrencyConversion(
                Math.round(amountInr * 100.0) / 100.0,
                Math.round(amountUsd * 100.0) / 100.0
        );
    }
}