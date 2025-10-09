package org.javacode.service;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Controller
public class FinancialDocumentProcessor {

    // Logger for the class
    private static final Logger logger = Logger.getLogger(FinancialDocumentProcessor.class.getName());

    // Reusable components
    private final HttpClient httpClient=HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(20))
            .build();;
    private final ObjectMapper objectMapper= new ObjectMapper();;
    @Value("${GOOGLE_API_KEY}")
    private String geminiApiKey;


    // --- 2. Java Records Mirroring Pydantic Models ---

    public enum ExpenseCategory {
        FOOD_AND_DINING, TRANSPORTATION, SHOPPING, ENTERTAINMENT, BILLS_AND_UTILITIES, HEALTHCARE, TRAVEL, EDUCATION, OTHERS
    }

    public enum IncomeCategory {
        SALARY, BUSINESS, INVESTMENTS, GIFTS, FREELANCE, RENTAL_INCOME, INTEREST, OTHERS
    }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record FinancialDocument(
            String userId,
            @JsonProperty("vendor") String name, // Fix: Map JSON "vendor" to "name"
            double amount,
            @JsonProperty("transactionType") String type, // Fix: Map JSON "transactionType" to "type"
            ExpenseCategory expenseCategory,
            IncomeCategory incomeCategory,
            String currency,
            String description
    ) {
        // Validation logic similar to @model_validator
        @JsonCreator
        public FinancialDocument {
            if ("Expense".equals(type)) {
                if (expenseCategory == null) throw new IllegalArgumentException("expenseCategory must be set for Expense transactions.");
                if (incomeCategory != null) throw new IllegalArgumentException("incomeCategory must not be set for Expense transactions.");
            } else if ("Income".equals(type)) {
                if (incomeCategory == null) throw new IllegalArgumentException("incomeCategory must be set for Income transactions.");
                if (expenseCategory != null) throw new IllegalArgumentException("expenseCategory must not be set for Income transactions.");
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

    // New DTO class as requested by the user
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record CreateEntryResponse(
            String userId,
            String name,
            String amount,
            String type,
            String expenseCategory,
            String incomeCategory,
            String currency,
            String description
    ) {}

    // --- 3. Helper and Core Logic Methods ---

    /**
     * Cleans input text by removing common problematic characters.
     */
    public String sanitizeText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String sanitized = text.replace("\u00A0", " ");
        sanitized = sanitized.replaceAll("\\s+", " ").trim();
        return sanitized;
    }

    /**
     * Gets exchange rates for USD and INR using the Frankfurter API.
     */
    public Map<String, Object> getExchangeRates(String baseCurrency) {
        if ("₹".equals(baseCurrency)) baseCurrency = "INR";
        String targets = "USD,INR";
        if ("USD".equalsIgnoreCase(baseCurrency)) targets = "INR";
        if ("INR".equalsIgnoreCase(baseCurrency)) targets = "USD";

        String currencyApiUrl = "https://api.frankfurter.app/latest";
        String apiUrl = String.format("%s?from=%s&to=%s", currencyApiUrl, baseCurrency.toUpperCase(), targets);
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(apiUrl)).GET().build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
                return result;
            } else {
                logger.log(Level.SEVERE, "Currency API request failed with status code: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            logger.log(Level.SEVERE, "Error calling currency API", e);
            Thread.currentThread().interrupt();
        }
        return Map.of("rates", Map.of("USD", 0.0, "INR", 0.0), "date", LocalDate.now().toString());
    }

    /**
     * Uses the Gemini model to extract financial data from text.
     */
    public FinancialDocument extractFinancialDataWithGemini(String text, String userId) {
        // --- MODIFIED PROMPT ---
        String prompt = """
        You are an expert financial data entry assistant. Analyze the raw text from a financial document.

        **Crucial Instructions:**
        1.  Identify the transaction 'type' and return it in a field named "transactionType".
        2.  Based on the 'type', you MUST categorize it.
            - If 'type' is 'Expense', for 'expenseCategory' you MUST choose one of: [FOOD_AND_DINING, TRANSPORTATION, SHOPPING, ENTERTAINMENT, BILLS_AND_UTILITIES, HEALTHCARE, TRAVEL, EDUCATION, OTHERS]. 'incomeCategory' must be null.
            - If 'type' is 'Income', for 'incomeCategory' you MUST choose one of: [SALARY, BUSINESS, INVESTMENTS, GIFTS, FREELANCE, RENTAL_INCOME, INTEREST, OTHERS]. 'expenseCategory' must be null.
        3.  Identify the 'name' of the vendor or source and return it in a field named "vendor".
        4.  Identify the total 'amount' and the 'currency' (use 3-letter ISO code like INR for ₹).
        5.  Create a brief 'description' summarizing the transaction (e.g., "Grocery shopping at Reliance Fresh Mart").

        **Output Format:**
        Provide the output ONLY in valid JSON format, with camelCase keys. Do not include 'userId'.

        ---
        Raw Text to Analyze:
        %s
        ---
        """.formatted(text);

        // FIXED: Updated API URL format for newer Gemini models
        String geminiModelName = "gemini-2.5-flash-lite";
        String geminiApiUrl = String.format("https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s",
                geminiModelName, geminiApiKey);

        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(Map.of(
                    "contents", List.of(Map.of(
                            "parts", List.of(Map.of("text", prompt))
                    ))
            ));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to create Gemini request body", e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(geminiApiUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                // IMPROVED: Better error logging with response details
                String errorMessage = String.format("Gemini API request failed with status: %d%nRequest URL: %s%nResponse Body: %s",
                        response.statusCode(), geminiApiUrl, response.body());
                logger.log(Level.SEVERE, errorMessage);
                throw new IOException(errorMessage);
            }

            JsonNode rootNode = objectMapper.readTree(response.body());
            String llmResponseText = rootNode.at("/candidates/0/content/parts/0/text").asText();

            String cleanJson = llmResponseText.strip().replaceFirst("```json", "").replaceFirst("```", "").strip();
            JsonNode dataNode = objectMapper.readTree(cleanJson);

            if (!dataNode.isObject()) {
                throw new IOException("LLM returned non-object JSON: " + cleanJson);
            }

            ObjectNode objectDataNode = (ObjectNode) dataNode;
            objectDataNode.put("userId", userId);

            return objectMapper.treeToValue(objectDataNode, FinancialDocument.class);

        } catch (IOException | InterruptedException e) {
            logger.log(Level.SEVERE, "Failed to process text with LLM", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Failed to process text with LLM", e);
        }
    }

    /**
     * Main processing function orchestrating the full workflow.
     */
    public ProcessedFinancialDocument processDocumentAndConvert(DocumentInput data) {
        String cleanText = sanitizeText(data.rawText());
        if (cleanText.length() < 10) {
            throw new IllegalArgumentException("Sanitized text is too short to process.");
        }

        FinancialDocument extractedData = extractFinancialDataWithGemini(cleanText, data.userId());

        Map<String, Object> exchangeData = getExchangeRates(extractedData.currency());
        @SuppressWarnings("unchecked")
        Map<String, Number> rates = (Map<String, Number>) exchangeData.get("rates");

        double rateUsd = rates.getOrDefault("USD", 0.0).doubleValue();
        double rateInr = rates.getOrDefault("INR", 0.0).doubleValue();
        double originalAmount = extractedData.amount();

        double amountUsd, amountInr;

        if ("USD".equalsIgnoreCase(extractedData.currency())) {
            amountUsd = originalAmount;
            amountInr = originalAmount * rateInr;
        } else if ("INR".equalsIgnoreCase(extractedData.currency())) {
            amountInr = originalAmount;
            amountUsd = originalAmount * rateUsd;
        } else {
            amountUsd = originalAmount * rateUsd;
            amountInr = originalAmount * rateInr;
        }

        return new ProcessedFinancialDocument(
                extractedData,
                Math.round(amountInr * 100.0) / 100.0,
                Math.round(amountUsd * 100.0) / 100.0,
                (String) exchangeData.get("date")
        );
    }

}