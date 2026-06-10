package com.upsertservice.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "shared_expenses", schema = "groups", indexes = {
    @Index(name = "idx_shared_expense_group", columnList = "group_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SharedExpense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @NotBlank
    @Size(max = 100)
    @Column(nullable = false, length = 100)
    private String description;

    @NotNull
    @DecimalMin("0.01")
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "paid_by", nullable = false)
    private UUID paidBy;   // userId of who paid

    @Enumerated(EnumType.STRING)
    @Column(name = "split_type", nullable = false)
    private SplitType splitType = SplitType.EQUAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "expense_category")
    private ExpenseCategory expenseCategory;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum SplitType {
        EQUAL, PERCENTAGE, EXACT
    }

    public enum ExpenseCategory {

        // ── Housing & Utilities ───────────────────────────────────────────────────
        RENT,               // split from HOUSING
        HOME_LOAN_EMI,
        HOME_MAINTENANCE,   // repairs, painting, plumbing
        PROPERTY_TAX,
        ELECTRICITY,        // split from UTILITIES
        WATER,
        GAS,
        INTERNET,
        MOBILE_PHONE,
        OTT_SUBSCRIPTIONS,  // Netflix, Hotstar, Spotify

        // ── Food & Dining ─────────────────────────────────────────────────────────
        GROCERIES,          // split from FOOD_AND_DINING
        RESTAURANTS,
        FOOD_DELIVERY,      // Swiggy, Zomato
        COFFEE_AND_SNACKS,

        // ── Transport ─────────────────────────────────────────────────────────────
        FUEL,               // split from TRANSPORT
        PUBLIC_TRANSPORT,   // bus, metro, train
        CAB_AND_AUTO,       // Ola, Uber, auto
        VEHICLE_EMI,
        VEHICLE_MAINTENANCE,
        PARKING_AND_TOLLS,
        FLIGHT_AND_TRAIN,   // intercity travel

        // ── Health ────────────────────────────────────────────────────────────────
        DOCTOR_AND_CLINIC,  // split from HEALTHCARE
        MEDICINES,
        HEALTH_INSURANCE,
        GYM_AND_FITNESS,
        MENTAL_WELLNESS,

        // ── Education ─────────────────────────────────────────────────────────────
        TUITION_AND_FEES,   // split from EDUCATION
        BOOKS_AND_COURSES,
        COACHING,
        STUDENT_LOAN_EMI,

        // ── Shopping ──────────────────────────────────────────────────────────────
        CLOTHING,           // split from SHOPPING
        ELECTRONICS,
        HOME_APPLIANCES,
        PERSONAL_CARE,      // cosmetics, haircut, salon
        GIFTS_GIVEN,

        // ── Entertainment & Lifestyle ─────────────────────────────────────────────
        MOVIES_AND_EVENTS,  // split from ENTERTAINMENT
        GAMING,
        SPORTS_AND_HOBBIES,
        BOOKS_AND_MAGAZINES,
        TRAVEL_VACATION,    // split from TRAVEL — leisure trips
        HOTEL_AND_STAYS,

        // ── Finance & Insurance ───────────────────────────────────────────────────
        LIFE_INSURANCE,     // split from INSURANCE
        VEHICLE_INSURANCE,
        CREDIT_CARD_PAYMENT,
        LOAN_REPAYMENT,
        MUTUAL_FUNDS_SIP,
        STOCKS_AND_TRADING,
        CRYPTO,
        EMERGENCY_FUND,
        FIXED_DEPOSIT,

        // ── Miscellaneous ─────────────────────────────────────────────────────────
        CHARITY_AND_DONATIONS,
        TAXES,
        FINES_AND_PENALTIES,
        PETS,
        CHILDCARE,
        ELDER_CARE,
        OTHERS
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
