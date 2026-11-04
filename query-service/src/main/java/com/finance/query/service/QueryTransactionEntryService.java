package com.finance.query.service;

import com.finance.query.dto.CreateEntryResponse;
import com.finance.query.model.Category;
import com.finance.query.model.ExpenseGroup;
import com.finance.query.model.ExpenseSplit;
import com.finance.query.model.ExpenseTransactionLink;
import com.finance.query.model.SharedExpense;
import com.finance.query.model.TransactionEntry;
import com.finance.query.model.TransactionType;
import com.finance.query.repository.ExpenseGroupRepository;
import com.finance.query.repository.ExpenseSplitRepository;
import com.finance.query.repository.ExpenseTransactionLinkRepository;
import com.finance.query.repository.SharedExpenseRepository;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QueryTransactionEntryService {

    private final TransactionEntryRepository repository;
    private final ExpenseTransactionLinkRepository linkRepository;
    private final SharedExpenseRepository sharedExpenseRepository;
    private final ExpenseGroupRepository expenseGroupRepository;
    private final ExpenseSplitRepository expenseSplitRepository;

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
    @Cacheable(value = "transactions", key = "#userId + ':' + (#type != null ? #type : 'ALL') + ':' + (#category != null ? #category : 'ALL_CATS') + ':' + (#startDate != null ? #startDate : 'MIN') + ':' + (#endDate != null ? #endDate : 'MAX') + ':' + #page + ':' + #size", sync = true)
    public Page<TransactionEntry> getEntriesByUserId(
            UUID userId, TransactionType type, Category category,
            LocalDate startDate, LocalDate endDate,
            int page, int size) {

        LocalDateTime start = startDate != null ? startDate.atStartOfDay()          : null;
        LocalDateTime end   = endDate   != null ? endDate.atTime(23, 59, 59) : null;

        Page<TransactionEntry> result = repository.findByUserIdAndFilters(userId, type, category, start, end, PageRequest.of(page, size));
        enrichGroupContext(result.getContent());
        return result;
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "transactions", key = "#userId + ':search:' + #query + ':' + #page + ':' + #size", sync = true)
    public Page<TransactionEntry> searchEntries(UUID userId, String query, int page, int size) {
        Page<TransactionEntry> result = repository.searchByUserId(userId, query, PageRequest.of(page, size));
        enrichGroupContext(result.getContent());
        return result;
    }

    /**
     * Attach the group name, the shared total and this user's share to ledger
     * rows that were created from a shared expense. The link table already
     * records the relationship; resolving it here keeps the client from
     * dividing a total or showing a bare "no group" split row.
     */
    private void enrichGroupContext(List<TransactionEntry> entries) {
        if (entries == null || entries.isEmpty()) return;

        List<Long> entryIds = new ArrayList<>();
        for (TransactionEntry entry : entries) {
            if (entry.getId() != null) entryIds.add(entry.getId());
        }
        if (entryIds.isEmpty()) return;

        List<ExpenseTransactionLink> links = linkRepository.findByTransactionEntryIdIn(entryIds);
        if (links.isEmpty()) return;

        Map<Long, Long> entryToExpense = new HashMap<>();
        for (ExpenseTransactionLink link : links) {
            if (link.getTransactionEntryId() != null && link.getSharedExpenseId() != null) {
                entryToExpense.put(link.getTransactionEntryId(), link.getSharedExpenseId());
            }
        }
        if (entryToExpense.isEmpty()) return;

        List<Long> expenseIds = new ArrayList<>(entryToExpense.values().stream().distinct().toList());
        Map<Long, SharedExpense> expenses = new HashMap<>();
        for (SharedExpense expense : sharedExpenseRepository.findAllById(expenseIds)) {
            expenses.put(expense.getId(), expense);
        }

        List<Long> groupIds = new ArrayList<>();
        for (SharedExpense expense : expenses.values()) {
            if (expense.getGroupId() != null && !groupIds.contains(expense.getGroupId())) {
                groupIds.add(expense.getGroupId());
            }
        }
        Map<Long, ExpenseGroup> groups = new HashMap<>();
        for (ExpenseGroup group : expenseGroupRepository.findAllById(groupIds)) {
            groups.put(group.getId(), group);
        }

        Map<String, BigDecimal> shares = new HashMap<>();
        for (ExpenseSplit split : expenseSplitRepository.findBySharedExpenseIdIn(expenseIds)) {
            shares.merge(split.getSharedExpenseId() + ":" + split.getUserId(), split.getAmount(), BigDecimal::add);
        }

        for (TransactionEntry entry : entries) {
            Long expenseId = entryToExpense.get(entry.getId());
            if (expenseId == null) continue;
            SharedExpense expense = expenses.get(expenseId);
            if (expense == null) continue;
            entry.setGroupId(expense.getGroupId());
            entry.setTotalAmount(expense.getAmount());
            ExpenseGroup group = groups.get(expense.getGroupId());
            if (group != null) entry.setGroupName(group.getName());
            entry.setMyShare(shares.get(expenseId + ":" + entry.getUserId()));
        }
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "transactions",
            key = "#userId + ':summary:' + (#startDate != null ? #startDate : 'MIN') + ':' + (#endDate != null ? #endDate : 'MAX')",
            sync = true)
    public Map<String, Object> getSummary(UUID userId, LocalDate startDate, LocalDate endDate) {
        LocalDateTime start = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime end   = endDate   != null ? endDate.atTime(23, 59, 59) : null;

        List<TransactionEntry> all = repository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId)
                .stream()
                .filter(e -> start == null || !e.getCreatedAt().isBefore(start))
                .filter(e -> end == null || !e.getCreatedAt().isAfter(end))
                // A settlement moves money between people; it is neither income
                // nor spending, so it must not inflate either total.
                .filter(e -> e.getCategory() != Category.SETTLEMENT)
                .toList();

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

    /** Backwards-compatible all-time summary. */
    public Map<String, Object> getSummary(UUID userId) {
        return getSummary(userId, null, null);
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
                entry.isRecurring(), entry.getRecurringPeriod(), entry.getCreatedAt(), entry.getUpdatedAt(), entry.getReceiptUrl());
    }
}
