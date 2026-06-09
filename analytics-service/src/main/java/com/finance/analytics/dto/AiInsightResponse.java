package com.finance.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiInsightResponse {
    private List<Insight> insights;
    private String summary;
    private String generatedAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Insight {
        private String title;
        private String message;
        private String type;  // WARNING | TIP | ACHIEVEMENT
        private int priority; // 1 (highest) - 5 (lowest)
    }
}
