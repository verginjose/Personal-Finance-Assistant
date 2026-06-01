package com.upsertservice.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CacheEvictEvent {
    private UUID userId;
    private String operation;   // CREATE, UPDATE, DELETE, PATCH
    private Long transactionId;
    private long timestamp;

    public static CacheEvictEvent of(UUID userId, String operation, Long transactionId) {
        return new CacheEvictEvent(userId, operation, transactionId,
                System.currentTimeMillis());
    }
}