package com.upsertservice.dto;

import com.upsertservice.model.Category;
import com.upsertservice.model.TransactionType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class CreateEntryResponse {

    private Long id;
    private UUID userId;
    private String name;
    private BigDecimal amount;
    private TransactionType type;
    private Category category;
    private String currency;
    private String description;
    private boolean recurring;
    private com.upsertservice.model.RecurringPeriod recurringPeriod;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
