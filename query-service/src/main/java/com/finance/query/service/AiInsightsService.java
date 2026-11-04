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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiInsightsService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${cerebras.api.key:}")
    private String cerebrasApiKey;

    @Value("${cerebras.model:qwen-3.8-27b}")
    private String cerebrasModel;

    @Value("${cerebras.url:https://api.cerebras.ai/v1/chat/completions}")
    private String cerebrasUrl;

    private final TransactionEntryRepository repository;
    private final GoalForecastingService goalForecastingService;
    private final BudgetTrendService budgetTrendService;
    private final com.finance.query.repository.SavingsGoalRepository savingsGoalRepository;
    private final CacheManager cacheManager;

    /** Injected from {@link com.finance.query.config.HttpClientConfig}. */
    private final HttpClient httpClient;

    /**
     * Cache key version. Bump it whenever the prompt/output contract changes so
     * advice cached under the old rules (e.g. "Goal 1122") is not served for
     * the full 24-hour TTL after a deploy.
     */
    private static final String CACHE_KEY_VERSION = "v5:";

    public AiInsightResponse generateInsights(UUID userId) {
        return generateInsights(userId, null, null);
    }

    /**
     * Insights for an optional date range. Null bounds mean "unbounded", so
     * (null, null) keeps the all-time behaviour while a month range answers the
     * period currently selected in the UI.
     */
    @Cacheable(value = "ai-insights",
            key = "'v5:' + #userId + ':' + (#startDate != null ? #startDate : 'MIN') + ':' + (#endDate != null ? #endDate : 'MAX')")
    public AiInsightResponse generateInsights(UUID userId, LocalDate startDate, LocalDate endDate) {
        boolean ranged = startDate != null || endDate != null;
        LocalDateTime start = startDate != null ? startDate.atStartOfDay() : LocalDateTime.of(1970, 1, 1, 0, 0);
        LocalDateTime end = endDate != null ? endDate.atTime(23, 59, 59) : LocalDateTime.of(9999, 12, 31, 23, 59, 59);

        BigDecimal income  = safeAmount(ranged
                ? repository.getTotalAmountByTypeAndDateRange(userId, TransactionType.INCOME, start, end)
                : repository.getTotalAmountByType(userId, TransactionType.valueOf("INCOME")));
        BigDecimal expense = safeAmount(ranged
                ? repository.getTotalAmountByTypeAndDateRange(userId, TransactionType.EXPENSE, start, end)
                : repository.getTotalAmountByType(userId, TransactionType.valueOf("EXPENSE")));
        List<CategoryRow> categories = ranged
                ? repository.getExpenseCategoryAnalyticsByDateRange(userId, start, end)
                : repository.getAllCategoryAnalytics(userId);

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

        if (cerebrasApiKey == null || cerebrasApiKey.isBlank()) {
            log.warn("CEREBRAS_API_KEY not configured — returning rule-based insights");
            return buildRuleBasedInsights(income, expense, categories, goalForecasts, budgetTrends);
        }

        try {
            return callCerebras(prompt);
        } catch (Exception e) {
            log.error("Cerebras call failed, falling back to rule-based: {}", e.getMessage());
            return buildRuleBasedInsights(income, expense, categories, goalForecasts, budgetTrends);
        }
    }

    /**
     * Bypasses the cache and regenerates advice on demand (the UI's "Refresh"
     * button). Self-invocation of {@link #generateInsights(UUID, LocalDate, LocalDate)}
     * is deliberate: it skips the cache proxy so the user gets fresh advice.
     */
    public AiInsightResponse regenerateInsights(UUID userId) {
        return regenerateInsights(userId, null, null);
    }

    /** Period-aware refresh: evicts only the key for this range. */
    public AiInsightResponse regenerateInsights(UUID userId, LocalDate startDate, LocalDate endDate) {
        Cache cache = cacheManager.getCache("ai-insights");
        if (cache != null) {
            cache.evict(cacheKey(userId, startDate, endDate));
        }
        return generateInsights(userId, startDate, endDate);
    }

    private static String cacheKey(UUID userId, LocalDate startDate, LocalDate endDate) {
        return CACHE_KEY_VERSION + userId + ':'
                + (startDate != null ? startDate : "MIN") + ':'
                + (endDate != null ? endDate : "MAX");
    }

    // ── Prompt building ───────────────────────────────────────────────────────

    String buildPrompt(BigDecimal income, BigDecimal expense, List<CategoryRow> categories,
                       List<GoalForecastingService.GoalForecast> goalForecasts,
                       List<BudgetTrendService.BudgetTrend> budgetTrends) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a calm, precise personal-finance editor. Write plainly, in sentence case, with no hype and no gamification. ");
        sb.append("Every insight must cite a concrete category, count or percentage from the data below. Never invent a figure, never say 'all your goals' when there is one, ");
        sb.append("and never give generic advice that would fit any user. If there is nothing specific to say, return fewer insights. ");
        sb.append("Write titles and messages in sentence case. Write category names as normal words (for example 'electronics', 'restaurants'), never in ALL CAPS or Title Case. ");
        sb.append("Never write a currency amount (₹, $, € or £) in a title, message or summary: the app displays amounts from its own totals. Percentages and counts are fine. ");
        sb.append("The category list contains expense categories only. Do NOT use any emojis.\n\n");
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
                sb.append("  \"").append(goalName(gf)).append("\"");
                if (gf.getSavedAmount() != null && gf.getTargetAmount() != null) {
                    sb.append(" (saved ₹").append(gf.getSavedAmount())
                      .append(" of ₹").append(gf.getTargetAmount()).append(")");
                }
                sb.append(": ").append(gf.getMessage()).append("\n");
            }
            sb.append("\nWhen you mention a goal, use its name exactly as written in quotes above. ")
              .append("Never mention numeric goal IDs — the user does not see them.\n");
            sb.append("Provide suggestions on how to accelerate these goals by reducing spending in specific categories.\n");
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
Each title must be short (max 6 words, sentence case); each message 1-2 sentences and reference a concrete category, count or percentage — never a currency amount.
For a goal with a deadline, say whether the required monthly transfer is above or below what is actually being saved, without printing the amounts.
The summary must be one sentence in sentence case with no currency amounts.
Return ONLY valid JSON in this format (no markdown, no explanation):
{"insights":[{"title":"string","message":"string","type":"WARNING|TIP|ACHIEVEMENT","priority":1}],"summary":"string"}
""");
        return sb.toString();
    }

    // ── Cerebras HTTP call ────────────────────────────────────────────────────

    private AiInsightResponse callCerebras(String prompt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cerebrasModel);
        body.put("temperature", 0.3);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));

        String bodyJson = MAPPER.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(cerebrasUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + cerebrasApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new RuntimeException("Cerebras HTTP " + res.statusCode());
        }

        JsonNode root = MAPPER.readTree(res.body());
        String content = root.path("choices").get(0).path("message").path("content").asText();
        JsonNode parsed = MAPPER.readTree(content);

        AiInsightResponse response = MAPPER.treeToValue(parsed, AiInsightResponse.class);
        if (response == null || response.getInsights() == null || response.getInsights().isEmpty()) {
            throw new RuntimeException("Cerebras returned no usable insights");
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
                    "Spending exceeds income",
                    "You've spent more than you earned overall. Consider reviewing your biggest expense categories.",
                    "WARNING", 1));
        } else if (income.compareTo(BigDecimal.ZERO) > 0) {
            double savingsRate = net.doubleValue() / income.doubleValue() * 100;
            if (savingsRate >= 20) {
                insights.add(new AiInsightResponse.Insight(
                        "Great savings rate",
                        "You're saving " + String.format("%.1f", savingsRate) + "% of your income overall. Keep it up!",
                        "ACHIEVEMENT", 2));
            } else {
                insights.add(new AiInsightResponse.Insight(
                        "Boost your savings",
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
                            category + " is a large share",
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
                            category + " spending jumped",
                            category + " spending is up " + String.format("%.0f", change)
                                    + "% versus last month. Check whether this is a one-off or a new habit.",
                            "WARNING", 2));
                    increases++;
                } else if ("DECREASED".equalsIgnoreCase(trend.getTrend()) && change >= 10 && decreases < 2) {
                    insights.add(new AiInsightResponse.Insight(
                            category + " spending down",
                            category + " spending fell " + String.format("%.0f", change)
                                    + "% versus last month. Consider moving the difference into savings.",
                            "ACHIEVEMENT", 3));
                    decreases++;
                }
            }
        }

        // Goal status: name the goal, the required monthly saving, and the
        // actual one. No plural template for a single goal.
        if (goals != null && !goals.isEmpty()) {
            int added = 0;
            for (GoalForecastingService.GoalForecast g : goals) {
                if (added >= 2) break;
                String name = goalName(g);
                BigDecimal velocity = g.getMonthlyVelocity();
                if (velocity == null || velocity.compareTo(BigDecimal.ZERO) <= 0) {
                    insights.add(new AiInsightResponse.Insight(
                            name + " has no momentum",
                            name + " has no monthly contributions yet. Automate a small transfer to get it moving.",
                            "TIP", 3));
                    added++;
                } else if (g.getRequiredMonthly() != null) {
                    boolean behind = Boolean.FALSE.equals(g.getOnTrack());
                    insights.add(new AiInsightResponse.Insight(
                            name + (behind ? " is behind" : " is on track"),
                            name + (behind
                            ? " needs a larger monthly transfer than you are making now."
                            : " is on track: your monthly transfer covers what the deadline needs."),
                            behind ? "WARNING" : "ACHIEVEMENT", behind ? 1 : 3));
                    added++;
                }
            }
        }

        normalizeInsights(insights);

        // The summary should add something the figures strip does not already
        // say, so it names where the money actually went.
        String summary;
        if (expense.compareTo(BigDecimal.ZERO) > 0 && categories != null && !categories.isEmpty()
                && categories.get(0).getCategory() != null && categories.get(0).getTotalAmount() != null) {
            CategoryRow top = categories.get(0);
            double share = top.getTotalAmount().doubleValue() / expense.doubleValue() * 100;
            summary = "Most of your spending went to " + prettyCategory(top.getCategory())
                    + " (" + String.format("%.0f", share) + "%).";
        } else if (net.compareTo(BigDecimal.ZERO) < 0) {
            summary = "Spending exceeds income.";
        } else {
            summary = "No spending to break down yet.";
        }

        return new AiInsightResponse(insights, summary,
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    }

    /** Goal names are the only thing the user (and the LLM) should ever see. */
    private static String goalName(GoalForecastingService.GoalForecast forecast) {
        String name = forecast.getGoalName();
        return name == null || name.isBlank() ? "Savings goal" : name.trim();
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
                        "No transactions yet",
                        "Start logging your income and expenses to receive personalized financial insights.",
                        "TIP", 1)),
                "Add your first transaction to unlock AI-powered insights.",
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    }

    private BigDecimal safeAmount(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
