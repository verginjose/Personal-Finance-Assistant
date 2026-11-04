package com.finance.query.service;

import com.finance.query.model.SavingsGoal;
import com.finance.query.repository.SavingsGoalRepository;
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
        /** Human-readable goal name from the savings goal, never shown to the LLM as an id. */
        private String goalName;
        private BigDecimal targetAmount;
        private BigDecimal savedAmount;
        private BigDecimal monthlyVelocity;
        /** What the user must save each month to hit the deadline. */
        private BigDecimal requiredMonthly;
        /** False when the current velocity will miss the deadline. */
        private Boolean onTrack;
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

        return forecastGoal(goal);
    }

    public GoalForecast forecastGoal(SavingsGoal goal) {
        GoalForecast forecast = new GoalForecast();
        forecast.setGoalId(goal.getId());
        forecast.setGoalName(goal.getName());
        forecast.setTargetAmount(goal.getTargetAmount());
        forecast.setSavedAmount(goal.getSavedAmount());

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

        // "On track" is a claim about the deadline, not about whether the
        // balance went up. Compare what the goal needs each month with what
        // the user is actually saving.
        if (goal.getDeadline() != null) {
            long monthsLeft = ChronoUnit.MONTHS.between(LocalDate.now(), goal.getDeadline());
            if (monthsLeft < 1) monthsLeft = 1;
            BigDecimal required = remaining.divide(BigDecimal.valueOf(monthsLeft), 2, RoundingMode.CEILING);
            boolean onTrack = velocity.compareTo(required) >= 0;
            forecast.setRequiredMonthly(required);
            forecast.setOnTrack(onTrack);
            forecast.setMessage((onTrack ? "On track: " : "Behind: ")
                    + "needs " + money(required) + "/mo, saving " + money(velocity) + "/mo"
                    + " for the deadline in " + monthsLeft + " month" + (monthsLeft == 1 ? "" : "s") + ".");
        } else {
            forecast.setMessage("At " + money(velocity) + "/mo, this finishes in "
                    + monthsRemaining + " months.");
        }

        return forecast;
    }

    private static String money(BigDecimal value) {
        return "₹" + value.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }
}
