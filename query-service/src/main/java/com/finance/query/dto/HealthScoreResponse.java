package com.finance.query.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class HealthScoreResponse {
    private int totalScore;         // 0–1000
    private String grade;           // A+ / A / B / C / D / F
    private Map<String, Integer> breakdown; // dimension -> score
    private String summary;
    private String calculatedAt;

    public static String toGrade(int score) {
        if (score >= 900) return "A+";
        if (score >= 800) return "A";
        if (score >= 700) return "B";
        if (score >= 600) return "C";
        if (score >= 500) return "D";
        return "F";
    }
}
