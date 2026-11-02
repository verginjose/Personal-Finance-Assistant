package com.finance.query.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.query.dto.AiInsightResponse;
import com.finance.query.dto.CategoryRow;
import com.finance.query.model.Category;
import com.finance.query.model.TransactionType;
import com.finance.query.repository.TransactionEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiInsightsService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${groq.api.key:}")
    private String groqApiKey;

    @Value("${groq.model:llama-3.1-8b-instant}")
    private String groqModel;

    @Value("${groq.url:https://api.groq.com/openai/v1/chat/completions}")
    private String groqUrl;

    private final TransactionEntryRepository repository;
    private final GoalForecastingService goalForecastingService;
    private final BudgetTrendService budgetTrendService;
    private final com.finance.query.repository.SavingsGoalRepository savingsGoalRepository;
    private final CacheManager cacheManager;

    /** Injected from {@link com.finance.query.config.HttpClientConfig}. */
    private final HttpClient httpClient;

    // Cache AI insights for 24 hours per user (avoid repeated LLM calls)
    @Cacheable(value = "ai-insights", key = "#userId")
    public AiInsightResponse generateInsights(UUID userId) {
        BigDecimal income  = safeAmount(repository.getTotalAmountByType(userId, TransactionType.valueOf("INCOME")));
        BigDecimal expense = safeAmount(repository.getTotalAmountByType(userId, TransactionType.valueOf("EXPENSE")));
        List<CategoryRow> categories = repository.getAllCategoryAnalytics(userId);

        if (income.compareTo(BigDecimal.ZERO) == 0 && expense.compareTo(BigDecimal.ZERO) == 0) {
            return buildDefaultResponse();
        }

        // Fetch goals & budgets
        List<GoalForecastingService.GoalForecast> goalForecasts = new ArrayList<>();
        for (com.finance.query.model.SavingsGoal goal : savingsGoalRepository.findByUserIdAndActiveTrueOrderByCreatedAtDesc(userId)) {
            goalForecasts.add(goalForecastingService.forecastGoal(goal));
        }
        List<BudgetTrendService.BudgetTrend> budgetTrends = budgetTrendService.getTrends(userId);

        String prompt = buildPrompt(income, expense, categories, goalForecasts, budgetTrends);

        if (groqApiKey == null || groqApiKey.isBlank()) {
            log.warn("GROQ_API_KEY not configured — returning rule-based insights");
            return buildRuleBasedInsights(income, expense, categories, goalForecasts, budgetTrends);
        }

        try {
            return callGroq(prompt);
        } catch (Exception e) {
            log.error("Groq call failed, falling back to rule-based: {}", e.getMessage());
            return buildRuleBasedInsights(income, expense, categories, goalForecasts, budgetTrends);
        }
    }

    /**
     * Bypasses the 24-hour cache and regenerates advice on demand (the UI's
     * "Refresh" button). Self-invocation of {@link #generateInsights(UUID)} is
     * deliberate: it skips the cache proxy so the user gets fresh advice.
     */
    public AiInsightResponse regenerateInsights(UUID userId) {
        Cache cache = cacheManager.getCache("ai-insights");
        if (cache != null) {
            cache.evict(userId);
        }
        return generateInsights(userId);
    }

    // ── Prompt building ───────────────────────────────────────────────────────

    String buildPrompt(BigDecimal income, BigDecimal expense, List<CategoryRow> categories,
                       List<GoalForecastingService.GoalForecast> goalForecasts,
                       List<BudgetTrendService.BudgetTrend> budgetTrends) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a highly engaging 'Financial Game Master' AI coach. Your goal is to gamify this user's finances and hype them up! ");
        sb.append("Analyze their financial data and provide 3-5 hyper-specific, actionable insights using gaming terminology (e.g., 'Level Up!', 'Combo Breaker', 'XP Gained'). ");
        sb.append("Make it fun, energetic, and slightly competitive. Do NOT use any emojis in your response. Avoid generic advice—be specific to their data.\n\n");
        sb.append("Overall Summary:\n");
        sb.append("- Total Income: ₹").append(income).append("\n");
        sb.append("- Total Expenses: ₹").append(expense).append("\n");
        sb.append("- Net Balance: ₹").append(income.subtract(expense)).append("\n");

        if (!categories.isEmpty()) {
            sb.append("- Top Expense Categories:\n");
            int limit = Math.min(categories.size(), 5);
            for (int i = 0; i < limit; i++) {
                CategoryRow row = categories.get(i);
                sb.append("  ").append(row.getCategory()).append(": ₹").append(row.getTotalAmount()).append("\n");
            }
        }

        if (!goalForecasts.isEmpty()) {
            sb.append("\n- Active Savings Goals:\n");
            for (GoalForecastingService.GoalForecast gf : goalForecasts) {
                sb.append("  Goal ").append(gf.getGoalId()).append(": ").append(gf.getMessage()).append("\n");
            }
            sb.append("\nProvide suggestions on how to accelerate these goals by reducing spending in specific categories.\n");
        }

        if (!budgetTrends.isEmpty()) {
            sb.append("\n- Budget Trends (MoM):\n");
            for (BudgetTrendService.BudgetTrend bt : budgetTrends) {
                sb.append("  ").append(bt.getCategory()).append(": ").append(bt.getTrend())
                  .append(" by ").append(String.format("%.1f", bt.getPercentageChange())).append("%\n");
            }
        }

        sb.append("""

Priority must be 1 (most urgent) to 5 (nice to have). Return 3-5 insights, ordered by priority.
Each title must be short (max 6 words); each message 1-2 sentences and reference a concrete number or category.
Return ONLY valid JSON in this format (no markdown, no explanation):
{"insights":[{"title":"string","message":"string","type":"WARNING|TIP|ACHIEVEMENT","priority":1}],"summary":"string"}
""");
        return sb.toString();
    }

    // ── Groq HTTP call ────────────────────────────────────────────────────────

    private AiInsightResponse callGroq(String prompt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", groqModel);
        body.put("temperature", 0.3);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));

        String bodyJson = MAPPER.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(groqUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + groqApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new RuntimeException("Groq HTTP " + res.statusCode());
        }

        JsonNode root = MAPPER.readTree(res.body());
        String content = root.path("choices").get(0).path("message").path("content").asText();
        JsonNode parsed = MAPPER.readTree(content);

        AiInsightResponse response = MAPPER.treeToValue(parsed, AiInsightResponse.class);
        if (response == null || response.getInsights() == null || response.getInsights().isEmpty()) {
            throw new RuntimeException("Groq returned no usable insights");
        }
        normalizeInsights(response.getInsights());
        if (response.getSummary() == null || response.getSummary().isBlank()) {
            response.setSummary("Here is what your recent activity suggests.");
        }
        response.setGeneratedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return response;
    }

    /** Coerces LLM output into a stable shape and orders it by priority. */
    static void normalizeInsights(List<AiInsightResponse.Insight> insights) {
        Set<String> allowed = Set.of("WARNING", "TIP", "ACHIEVEMENT", "INFO");
        insights.removeIf(Objects::isNull);
        for (AiInsightResponse.Insight insight : insights) {
            String type = insight.getType() == null ? "INFO" : insight.getType().trim().toUpperCase();
            insight.setType(allowed.contains(type) ? type : "INFO");

            if (insight.getTitle() == null || insight.getTitle().isBlank()) {
                insight.setTitle("Insight");
            }
            if (insight.getMessage() == null) {
                insight.setMessage("");
            }
            int priority = insight.getPriority();
            if (priority < 1) priority = 3;
            if (priority > 5) priority = 5;
            insight.setPriority(priority);
        }
        insights.sort(Comparator.comparingInt(AiInsightResponse.Insight::getPriority));
    }

    // ── Fallback: rule-based insights ─────────────────────────────────────────

    AiInsightResponse buildRuleBasedInsights(BigDecimal income, BigDecimal expense) {
        return buildRuleBasedInsights(income, expense,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    /**
     * Deterministic advisor used when the LLM is unavailable. It mirrors the
     * categories, budget trends and goals the LLM would see, so the UI always
     * has specific, varied advice instead of two generic lines.
     */
    AiInsightResponse buildRuleBasedInsights(BigDecimal income, BigDecimal expense,
                                             List<CategoryRow> categories,
                                             List<GoalForecastingService.GoalForecast> goals,
                                             List<BudgetTrendService.BudgetTrend> budgetTrends) {
        List<AiInsightResponse.Insight> insights = new ArrayList<>();
        BigDecimal net = income.subtract(expense);

        if (net.compareTo(BigDecimal.ZERO) < 0) {
            insights.add(new AiInsightResponse.Insight(
                    "Spending Exceeds Income",
                    "You've spent ₹" + expense.subtract(income).toPlainString() + " more than you earned overall. Consider reviewing your biggest expense categories.",
                    "WARNING", 1));
        } else if (income.compareTo(BigDecimal.ZERO) > 0) {
            double savingsRate = net.doubleValue() / income.doubleValue() * 100;
            if (savingsRate >= 20) {
                insights.add(new AiInsightResponse.Insight(
                        "Great Savings Rate!",
                        "You're saving " + String.format("%.1f", savingsRate) + "% of your income overall. Keep it up!",
                        "ACHIEVEMENT", 2));
            } else {
                insights.add(new AiInsightResponse.Insight(
                        "Boost Your Savings",
                        "Your overall savings rate is " + String.format("%.1f", savingsRate) + "%. Financial advisors recommend saving at least 20% of income.",
                        "TIP", 2));
            }
        }

        // Where the money actually goes
        if (expense.compareTo(BigDecimal.ZERO) > 0 && categories != null && !categories.isEmpty()) {
            CategoryRow top = categories.get(0);
            if (top.getCategory() != null && top.getTotalAmount() != null) {
                double share = top.getTotalAmount().doubleValue() / expense.doubleValue() * 100;
                if (share >= 25) {
                    String category = prettyCategory(top.getCategory());
                    insights.add(new AiInsightResponse.Insight(
                            "Watch " + category,
                            category + " accounts for " + String.format("%.0f", share)
                                    + "% of your spending. Trimming it is the fastest way to free up cash.",
                            "TIP", 2));
                }
            }
        }

        // Month-over-month budget movement
        if (budgetTrends != null) {
            int increases = 0, decreases = 0;
            for (BudgetTrendService.BudgetTrend trend : budgetTrends) {
                Double change = trend.getPercentageChange();
                if (change == null) continue;
                String category = prettyCategory(trend.getCategory());

                if ("INCREASED".equalsIgnoreCase(trend.getTrend()) && change >= 20 && increases < 2) {
                    insights.add(new AiInsightResponse.Insight(
                            category + " Spending Jumped",
                            category + " spending is up " + String.format("%.0f", change)
                                    + "% versus last month. Check whether this is a one-off or a new habit.",
                            "WARNING", 2));
                    increases++;
                } else if ("DECREASED".equalsIgnoreCase(trend.getTrend()) && change >= 10 && decreases < 2) {
                    insights.add(new AiInsightResponse.Insight(
                            category + " Spending Down",
                            category + " spending fell " + String.format("%.0f", change)
                                    + "% versus last month. Consider moving the difference into savings.",
                            "ACHIEVEMENT", 3));
                    decreases++;
                }
            }
        }

        // Goal momentum
        if (goals != null && !goals.isEmpty()) {
            boolean stalled = goals.stream().anyMatch(g -> g.getMonthlyVelocity() == null
                    || g.getMonthlyVelocity().compareTo(BigDecimal.ZERO) <= 0);
            if (stalled) {
                insights.add(new AiInsightResponse.Insight(
                        "Give Your Goals a Push",
                        "At least one savings goal has no monthly momentum yet. Automate a small transfer each month to get it moving.",
                        "TIP", 3));
            } else {
                insights.add(new AiInsightResponse.Insight(
                        "Goals Are Moving",
                        "Your savings goals all have positive monthly momentum. Keep the transfers consistent to stay on schedule.",
                        "ACHIEVEMENT", 3));
            }
        }

        insights.add(new AiInsightResponse.Insight(
                "Track Every Expense",
                "Consistent expense tracking is the foundation of financial health. Try to log every transaction the same day.",
                "TIP", 5));

        normalizeInsights(insights);

        String summary = net.compareTo(BigDecimal.ZERO) >= 0
                ? "Good job staying in the positive overall! Net balance: ₹" + net.toPlainString()
                : "Overall you overspent by ₹" + net.abs().toPlainString() + ". Review your spending patterns.";

        return new AiInsightResponse(insights, summary,
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    }

    private static String prettyCategory(Category category) {
        return category == null ? "This category" : prettyCategory(category.name());
    }

    private static String prettyCategory(String raw) {
        if (raw == null || raw.isBlank()) return "This category";
        StringBuilder out = new StringBuilder();
        for (String word : raw.replace('_', ' ').toLowerCase(Locale.ENGLISH).split(" ")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.length() == 0 ? "This category" : out.toString();
    }

    private AiInsightResponse buildDefaultResponse() {
        return new AiInsightResponse(
                List.of(new AiInsightResponse.Insight(
                        "No Transactions Yet",
                        "Start logging your income and expenses to receive personalized financial insights!",
                        "TIP", 1)),
                "Add your first transaction to unlock AI-powered insights.",
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    }

    private BigDecimal safeAmount(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
