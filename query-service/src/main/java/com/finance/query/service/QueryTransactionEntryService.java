package com.finance.query.service;

import com.finance.query.dto.CreateEntryResponse;
import com.finance.query.model.TransactionEntry;
import com.finance.query.model.TransactionType;
import com.finance.query.repository.TransactionEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QueryTransactionEntryService {

    private final TransactionEntryRepository repository;

    @Transactional(readOnly = true)
    public Page<CreateEntryResponse> getGoalContributions(Long goalId, UUID userId, int page, int size) {
        return repository.findByGoalIdAndUserId(goalId, userId, PageRequest.of(page, size))
                .map(this::convertToResponse);
    }

    @Transactional(readOnly = true)
    public CreateEntryResponse getEntryById(Long id, UUID userId) {
        TransactionEntry entry = repository.findByIdAndUserIdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction entry with ID " + id + " not found"));
        return convertToResponse(entry);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "transactions", key = "#userId + ':' + (#type != null ? #type : 'ALL') + ':' + (#startDate != null ? #startDate : 'MIN') + ':' + (#endDate != null ? #endDate : 'MAX') + ':' + #page + ':' + #size", sync = true)
    public Page<TransactionEntry> getEntriesByUserId(
            UUID userId, TransactionType type,
            LocalDate startDate, LocalDate endDate,
            int page, int size) {

        LocalDateTime start = startDate != null ? startDate.atStartOfDay()          : null;
        LocalDateTime end   = endDate   != null ? endDate.atTime(23, 59, 59) : null;

        return repository.findByUserIdAndFilters(userId, type, start, end, PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "transactions", key = "#userId + ':search:' + #query + ':' + #page + ':' + #size", sync = true)
    public Page<TransactionEntry> searchEntries(UUID userId, String query, int page, int size) {
        return repository.searchByUserId(userId, query, PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "transactions", key = "#userId + ':summary'", sync = true)
    public Map<String, Object> getSummary(UUID userId) {
        List<TransactionEntry> all = repository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId);
        BigDecimal income  = all.stream().filter(e -> e.getType() == TransactionType.INCOME)
                .map(TransactionEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expense = all.stream().filter(e -> e.getType() == TransactionType.EXPENSE)
                .map(TransactionEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalIncome",  income);
        summary.put("totalExpense", expense);
        summary.put("netBalance",   income.subtract(expense));
        summary.put("totalCount",   all.size());
        return summary;
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "transactions", key = "#userId + ':recurring'", sync = true)
    public List<TransactionEntry> getRecurringEntries(UUID userId) {
        return repository.findByUserIdAndRecurringTrueAndDeletedAtIsNull(userId);
    }

    @Transactional(readOnly = true)
    public String exportCsv(UUID userId) {
        List<TransactionEntry> all = repository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId);
        StringBuilder csv = new StringBuilder();
        csv.append("id,name,amount,type,expenseCategory,incomeCategory,currency,description,recurring,recurringPeriod,createdAt\n");
        for (TransactionEntry e : all) {
            csv.append(e.getId()).append(',')
               .append(escapeCsv(e.getName())).append(',')
               .append(e.getAmount()).append(',')
               .append(e.getType()).append(',')
               .append(e.getCategory() != null ? e.getCategory() : "").append(',')
               .append(e.getCurrency()).append(',')
               .append(escapeCsv(e.getDescription())).append(',')
               .append(e.isRecurring()).append(',')
               .append(e.getRecurringPeriod() != null ? e.getRecurringPeriod() : "").append(',')
               .append(e.getCreatedAt()).append('\n');
        }
        return csv.toString();
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private CreateEntryResponse convertToResponse(TransactionEntry entry) {
        return new CreateEntryResponse(
                entry.getId(), entry.getUserId(), entry.getName(), entry.getAmount(),
                entry.getType(), entry.getCategory(), entry.getCurrency(), entry.getDescription(),
                entry.isRecurring(), entry.getRecurringPeriod(), entry.getCreatedAt(), entry.getUpdatedAt());
    }
}
