package com.finance.command.model;

public enum Category {

    // ── Income ────────────────────────────────────────────────────────────────
    SALARY,
    FREELANCE,
    BUSINESS,
    INVESTMENTS,        // renamed from INVESTMENT for consistency
    RENTAL_INCOME,      // renamed from RENTAL
    DIVIDENDS,
    INTEREST,           // savings/FD interest
    BONUS,
    PENSION,
    GOVT_BENEFITS,      // PF, gratuity, subsidies
    CASHBACK_REWARDS,   // credit card cashback, rewards
    GIFTS_RECEIVED,     // renamed from GIFT
    TAX_REFUND,
    SIDE_HUSTLE,        // gig work, part-time
    OTHER_INCOME,

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
    GOAL,
    SETTLEMENT,
    OTHERS
}