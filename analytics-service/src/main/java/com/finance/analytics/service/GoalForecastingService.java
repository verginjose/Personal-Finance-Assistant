package com.finance.analytics.service;

import com.finance.analytics.model.SavingsGoal;
import com.finance.analytics.repository.SavingsGoalRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoalForecastingService {

    private final SavingsGoalRepository goalRepository;

    @Data
    public static class GoalForecast {
        private Long goalId;
        private BigDecimal monthlyVelocity;
        private Integer estimatedMonthsRemaining;
        private LocalDate estimatedCompletionDate;
        private String message;
    }

    public GoalForecast forecastGoal(Long goalId, UUID userId) {
        SavingsGoal goal = goalRepository.findById(goalId)
                .orElseThrow(() -> new IllegalArgumentException("Goal not found"));

        if (!goal.getUserId().equals(userId)) {
            throw new SecurityException("Not authorized");
        }

        GoalForecast forecast = new GoalForecast();
        forecast.setGoalId(goalId);

        if (goal.isCompleted()) {
            forecast.setMessage("Goal is already completed!");
            forecast.setEstimatedMonthsRemaining(0);
            return forecast;
        }

        if (goal.getSavedAmount().compareTo(BigDecimal.ZERO) == 0) {
            forecast.setMessage("No contributions yet. Start saving to see a forecast.");
            forecast.setMonthlyVelocity(BigDecimal.ZERO);
            return forecast;
        }

        // Calculate months since creation (minimum 1 to avoid division by zero)
        long monthsActive = ChronoUnit.MONTHS.between(goal.getCreatedAt().toLocalDate(), LocalDate.now());
        if (monthsActive < 1) monthsActive = 1;

        BigDecimal velocity = goal.getSavedAmount().divide(BigDecimal.valueOf(monthsActive), 2, RoundingMode.HALF_UP);
        forecast.setMonthlyVelocity(velocity);

        if (velocity.compareTo(BigDecimal.ZERO) <= 0) {
            forecast.setMessage("Not enough positive velocity to forecast.");
            return forecast;
        }

        BigDecimal remaining = goal.getTargetAmount().subtract(goal.getSavedAmount());
        int monthsRemaining = remaining.divide(velocity, RoundingMode.CEILING).intValue();
        
        forecast.setEstimatedMonthsRemaining(monthsRemaining);
        forecast.setEstimatedCompletionDate(LocalDate.now().plusMonths(monthsRemaining));
        forecast.setMessage("On track to finish in " + monthsRemaining + " months.");
        
        return forecast;
    }
}
