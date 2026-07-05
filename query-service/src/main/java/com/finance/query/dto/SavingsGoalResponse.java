package com.finance.query.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data @NoArgsConstructor @AllArgsConstructor
public class SavingsGoalResponse {
    private Long id;
    private String name;
    private BigDecimal targetAmount;
    private BigDecimal savedAmount;
    private double progressPercentage;
    private String currency;
    private String description;
    private LocalDate deadline;
    private boolean completed;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private com.finance.query.model.Priority priority;
}
