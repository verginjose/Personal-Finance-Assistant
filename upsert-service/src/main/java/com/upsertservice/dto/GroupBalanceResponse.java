package com.upsertservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Simplified balance view for a group member */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupBalanceResponse {

    private Long groupId;
    private String groupName;
    private List<MemberBalance> memberBalances;
    private List<SettlementSuggestion> simplifiedDebts;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemberBalance {
        private UUID userId;
        private String userName;
        /** positive = is owed money, negative = owes money */
        private BigDecimal netBalance;
        private BigDecimal totalPaid;
        private BigDecimal totalOwed;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SettlementSuggestion {
        private UUID fromUserId;
        private String fromUserName;
        private UUID toUserId;
        private String toUserName;
        private BigDecimal amount;
        private String currency;
    }
}
