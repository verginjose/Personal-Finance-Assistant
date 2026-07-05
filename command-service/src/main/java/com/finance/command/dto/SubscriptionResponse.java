package com.finance.command.dto;

import com.finance.command.model.RecurringPeriod;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionResponse {
    private Long id;
    private String name;
    private BigDecimal amount;
    private String currency;
    private RecurringPeriod period;
    private LocalDate nextChargeDate;
    private int daysUntilCharge;
    private boolean active;

    /** Human-readable alert, e.g. "Charge in 3 days" */
    public String getAlert() {
        if (!active) return "Inactive";
        if (daysUntilCharge == 0) return "⚠️ Charging today!";
        if (daysUntilCharge <= 3) return "⚠️ Charge in " + daysUntilCharge + " day(s)";
        if (daysUntilCharge <= 7) return "📅 Charge in " + daysUntilCharge + " days";
        return "Next charge: " + nextChargeDate;
    }
}
