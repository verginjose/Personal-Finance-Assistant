package com.finance.command.service;

import com.finance.command.dto.CreateEntryRequest;
import com.finance.command.dto.CreateEntryResponse;
import com.finance.command.dto.UpdateEntryRequest;
import com.finance.command.model.TransactionEntry;
import com.finance.command.model.TransactionType;
import com.finance.command.repository.TransactionEntryRepository;
import com.finance.command.repository.OutboxEventRepository;
import com.finance.command.model.OutboxEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.Cacheable;
import com.finance.command.cache.QueryCacheEvictor;
import com.finance.command.model.IdempotencyRecord;
import com.finance.command.repository.IdempotencyRecordRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@Transactional
public class TransactionEntryService {

    /**
     * Reserve an idempotency key atomically. The unique constraint on
     * (user_id, idempotency_key) is what arbitrates a concurrent double
     * submit: the second INSERT blocks until the first transaction commits
     * (→ 0 rows, replay the winner's row) or rolls back (→ 1 row, we own it).
     * ON CONFLICT DO NOTHING keeps the aborted transaction usable, unlike
     * catching a constraint violation.
     */
    private static final String RESERVE_IDEMPOTENCY_KEY = """
            INSERT INTO finance.idempotency_records (user_id, idempotency_key, created_at)
            VALUES (?, ?, now())
            ON CONFLICT (user_id, idempotency_key) DO NOTHING
            """;

    private static final String ATTACH_IDEMPOTENCY_TRANSACTION = """
            UPDATE finance.idempotency_records
               SET transaction_id = ?
             WHERE user_id = ? AND idempotency_key = ?
            """;

    private final TransactionEntryRepository repository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Counter createCounter;
    private final Counter deleteCounter;
    private final OutboxEventRepository outboxEventRepository;
    private final GoalBudgetService goalBudgetService;
    private final com.finance.command.repository.TransactionGoalAllocationRepository allocationRepository;
    private final com.finance.command.cache.QueryCacheEvictor queryCacheEvictor;
    private final com.finance.command.repository.ExpenseTransactionLinkRepository transactionLinkRepo;
    private final SubscriptionDetectorService subscriptionDetectorService;

    public TransactionEntryService(
            TransactionEntryRepository repository,
            IdempotencyRecordRepository idempotencyRecordRepository,
            JdbcTemplate jdbcTemplate,
            MeterRegistry meterRegistry, OutboxEventRepository outboxEventRepository,
            GoalBudgetService goalBudgetService,
            com.finance.command.repository.TransactionGoalAllocationRepository allocationRepository,
            com.finance.command.cache.QueryCacheEvictor queryCacheEvictor,
            com.finance.command.repository.ExpenseTransactionLinkRepository transactionLinkRepo,
            SubscriptionDetectorService subscriptionDetectorService
    ) {
        this.repository = repository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.createCounter = meterRegistry.counter("transactions.create.success");
        this.deleteCounter = meterRegistry.counter("transactions.delete.success");
        this.outboxEventRepository = outboxEventRepository;
        this.goalBudgetService = goalBudgetService;
        this.allocationRepository = allocationRepository;
        this.queryCacheEvictor = queryCacheEvictor;
        this.transactionLinkRepo = transactionLinkRepo;
        this.subscriptionDetectorService = subscriptionDetectorService;
    }

    // ── Create ────────────────────────────────────────────────────────────────

    public CreateEntryResponse createEntry(CreateEntryRequest request, String idempotencyKey) {
        return createEntry(request, idempotencyKey, true);
    }

    @Transactional
    public List<CreateEntryResponse> createBulkEntries(List<CreateEntryRequest> requests) {
        List<CreateEntryResponse> responses = new ArrayList<>();
        UUID userId = null;
        for (CreateEntryRequest req : requests) {
            if (userId == null) userId = req.getUserId();
            responses.add(createEntry(req, null, false));
        }
        if (userId != null && !responses.isEmpty()) {
            OutboxEvent event = new OutboxEvent();
            event.setUserId(userId);
            event.setEventType("CREATE");
            event.setEntityId(responses.get(0).getId());
            outboxEventRepository.save(event);
        }
        return responses;
    }

    public CreateEntryResponse createEntry(CreateEntryRequest request, String idempotencyKey, boolean publishCacheEvict) {
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        if (key != null && key.length() > 120) {
            throw new IllegalArgumentException("X-Idempotency-Key must be at most 120 characters");
        }
        boolean ownsKey = key != null && reserveIdempotencyKey(request.getUserId(), key);
        if (key != null && !ownsKey) {
            return replayIdempotentEntry(request.getUserId(), key);
        }

        TransactionEntry entry = getTransactionEntry(request);

        TransactionEntry saved = repository.save(entry);

        if (ownsKey) {
            jdbcTemplate.update(ATTACH_IDEMPOTENCY_TRANSACTION,
                    saved.getId(), request.getUserId(), key);
        }

        // A recurring expense is a subscription. Detect it now instead of
        // waiting for the 02:00 UTC job: GET /api/upsert/subscriptions is
        // routed to query-service, so the read side's on-demand detection is
        // unreachable through the gateway.
        if (saved.isRecurring() && saved.getType() == TransactionType.EXPENSE) {
            try {
                subscriptionDetectorService.processRecurringTransactions(List.of(saved));
            } catch (RuntimeException e) {
                log.warn("Subscription detection failed for transaction {}: {}",
                        saved.getId(), e.getMessage());
            }
        }

        if (publishCacheEvict) {
            OutboxEvent event = new OutboxEvent();
            event.setUserId(request.getUserId());
            event.setEventType("CREATE");
            event.setEntityId(saved.getId());
            outboxEventRepository.save(event);
        }
        createCounter.increment();
        queryCacheEvictor.evictUserKeysWithReplicationGuard(request.getUserId());

        log.info("Transaction created: id={}, user={}, recurring={}", saved.getId(), saved.getUserId(), saved.isRecurring());
        return convertToResponse(saved);
    }

    /**
     * @return true if this request inserted the reservation row and therefore
     *         owns the right to create the transaction. False means a
     *         concurrent request already holds the key: the INSERT waited for
     *         that transaction to finish, so its row (and transaction id) is
     *         now committed and visible.
     */
    private boolean reserveIdempotencyKey(UUID userId, String key) {
        int inserted = jdbcTemplate.update(RESERVE_IDEMPOTENCY_KEY, userId, key);
        if (inserted == 1) {
            log.debug("Reserved idempotency key {} for user {}", key, userId);
        } else {
            log.info("Idempotency key {} for user {} is already reserved; replaying", key, userId);
        }
        return inserted == 1;
    }

    private CreateEntryResponse replayIdempotentEntry(UUID userId, String key) {
        IdempotencyRecord record = idempotencyRecordRepository
                .findByUserIdAndIdempotencyKey(userId, key)
                .orElseThrow(() -> new IllegalStateException("Idempotency key vanished: " + key));
        Long transactionId = record.getTransactionId();
        if (transactionId == null) {
            throw new IllegalStateException(
                    "Idempotency key '" + key + "' is reserved but has no transaction attached");
        }
        TransactionEntry existing = repository.findByIdAndDeletedAtIsNull(transactionId)
                .orElseThrow(() -> new IllegalStateException("Idempotency key points to a missing transaction"));
        log.info("Replayed idempotent create for key {} -> transaction {}", key, transactionId);
        return convertToResponse(existing);
    }
    // TransactionEntryService
    public List<CreateEntryResponse> createEntries(List<CreateEntryRequest> requests, boolean publishCacheEvict) {
        if (requests.isEmpty()) {
            return List.of();
        }

        List<TransactionEntry> entries = requests.stream()
                .map(TransactionEntryService::getTransactionEntry)
                .toList();

        List<TransactionEntry> saved = repository.saveAll(entries);

        if (publishCacheEvict) {
            List<OutboxEvent> events = saved.stream().map(s -> {
                OutboxEvent event = new OutboxEvent();
                event.setUserId(s.getUserId());
                event.setEventType("CREATE");
                event.setEntityId(s.getId());
                return event;
            }).toList();
            outboxEventRepository.saveAll(events);
        }

        createCounter.increment(saved.size());

        // dedupe evictions — one per distinct user instead of one per entry
        saved.stream()
                .map(TransactionEntry::getUserId)
                .distinct()
                .forEach(queryCacheEvictor::evictUserKeysWithReplicationGuard);

        log.info("Batch created {} transaction entries", saved.size());

        return saved.stream().map(this::convertToResponse).toList();
    }

    private static TransactionEntry getTransactionEntry(CreateEntryRequest request) {
        TransactionEntry entry = new TransactionEntry(
                request.getUserId(), request.getName(),
                request.getAmount(), request.getType(), request.getCurrency()
        );
        log.info("Category got from the request {}",request.getCategory());
        entry.setCategory(request.getCategory());
        entry.setDescription(request.getDescription());
        entry.setRecurring(request.isRecurring());
        entry.setRecurringPeriod(request.getRecurringPeriod());
        if (request.isRecurring() && request.getRecurringPeriod() != null) {
            LocalDateTime baseDate = request.getCreatedAt() != null ? request.getCreatedAt() : LocalDateTime.now();
            entry.setNextRunDate(calculateNextRunDate(baseDate, request.getRecurringPeriod()));
        }
        if (request.getCreatedAt() != null) {
            entry.setCreatedAt(request.getCreatedAt());
        }
        entry.setReceiptUrl(request.getReceiptUrl());
        return entry;
    }

    public static LocalDateTime calculateNextRunDate(LocalDateTime from, com.finance.command.model.RecurringPeriod period) {
        if (period == null) return null;
        LocalDateTime next = switch (period) {
            case DAILY -> from.plusDays(1);
            case WEEKLY -> from.plusWeeks(1);
            case MONTHLY -> from.plusMonths(1);
            case YEARLY -> from.plusYears(1);
        };
        return next.toLocalDate().atStartOfDay();
    }

    // ── Update (full) ─────────────────────────────────────────────────────────

    public CreateEntryResponse updateEntry(UpdateEntryRequest request) {
        TransactionEntry existing = repository.findByIdAndDeletedAtIsNull(request.getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Transaction entry with ID " + request.getId() + " not found"));
        if (!existing.getUserId().equals(request.getUserId())) {
            throw new SecurityException("User is not authorized to update this transaction");
        }
        if (transactionLinkRepo.existsByTransactionEntryId(request.getId())) {
            throw new IllegalStateException("This transaction is managed by a group expense. Please modify the shared expense instead.");
        }
        BigDecimal oldAmount = existing.getAmount();
        BigDecimal diff = request.getAmount().subtract(oldAmount);

        existing.setName(request.getName());
        existing.setAmount(request.getAmount());
        existing.setType(request.getType());
        existing.setCurrency(request.getCurrency());
        existing.setDescription(request.getDescription());
        existing.setCategory(request.getCategory());
        existing.setRecurring(request.isRecurring());
        existing.setRecurringPeriod(request.getRecurringPeriod());
        
        if (request.getCreatedAt() != null) {
            existing.setCreatedAt(request.getCreatedAt());
        }
        existing.setReceiptUrl(request.getReceiptUrl());
        
        TransactionEntry updated = repository.save(existing);
        
        if (diff.compareTo(BigDecimal.ZERO) != 0) {
            java.util.Optional<com.finance.command.model.TransactionGoalAllocation> allocationOpt = allocationRepository.findByTransactionId(updated.getId());
            if (allocationOpt.isPresent()) {
                com.finance.command.model.TransactionGoalAllocation alloc = allocationOpt.get();
                alloc.setAmount(alloc.getAmount().add(diff));
                allocationRepository.save(alloc);
                try {
                    goalBudgetService.adjustGoalSavedAmount(alloc.getGoalId(), request.getUserId(), diff);
                } catch (Exception e) {
                    log.warn("Failed to update goal allocation for goal {}: {}", alloc.getGoalId(), e.getMessage());
                }
            }
        }
        
        OutboxEvent event = new OutboxEvent();
        event.setUserId(request.getUserId());
        event.setEventType("UPDATE");
        event.setEntityId(updated.getId());
        outboxEventRepository.save(event);
        
        queryCacheEvictor.evictUserKeysWithReplicationGuard(request.getUserId());
        
        log.info("Transaction updated: id={}", updated.getId());
        return convertToResponse(updated);
    }

    // ── Patch amount (partial update) ─────────────────────────────────────────

    public CreateEntryResponse patchAmount(Long id, UUID userId, BigDecimal newAmount) {
        TransactionEntry entry = repository.findByIdAndUserIdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Transaction entry with ID " + id + " not found for user " + userId));
        BigDecimal oldAmount = entry.getAmount();
        BigDecimal diff = newAmount.subtract(oldAmount);

        entry.setAmount(newAmount);
        TransactionEntry saved = repository.save(entry);
        
        if (diff.compareTo(BigDecimal.ZERO) != 0) {
            java.util.Optional<com.finance.command.model.TransactionGoalAllocation> allocationOpt = allocationRepository.findByTransactionId(saved.getId());
            if (allocationOpt.isPresent()) {
                com.finance.command.model.TransactionGoalAllocation alloc = allocationOpt.get();
                alloc.setAmount(alloc.getAmount().add(diff));
                allocationRepository.save(alloc);
                try {
                    goalBudgetService.adjustGoalSavedAmount(alloc.getGoalId(), userId, diff);
                } catch (Exception e) {
                    log.warn("Failed to update goal allocation for goal {}: {}", alloc.getGoalId(), e.getMessage());
                }
            }
        }
        
        OutboxEvent event = new OutboxEvent();
        event.setUserId(userId);
        event.setEventType("PATCH");
        event.setEntityId(id);
        outboxEventRepository.save(event);
        
        queryCacheEvictor.evictUserKeysWithReplicationGuard(userId);
        
        log.info("Transaction amount patched: id={}, newAmount={}", id, newAmount);
        return convertToResponse(saved);
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    public void deleteEntry(Long id, UUID userId) {
        if (transactionLinkRepo.existsByTransactionEntryId(id)) {
            throw new IllegalStateException("This transaction is managed by a group expense. Please modify the shared expense instead.");
        }
        deleteEntryInternal(id, userId);
    }

    public void deleteEntryInternal(Long id, UUID userId) {
        TransactionEntry entry = repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Transaction entry with ID " + id + " not found"));
        if (!entry.getUserId().equals(userId)) {
            throw new SecurityException("User is not authorized to delete this transaction");
        }
        
        // Revert goal allocations
        java.util.Optional<com.finance.command.model.TransactionGoalAllocation> allocationOpt = allocationRepository.findByTransactionId(id);
        if (allocationOpt.isPresent()) {
            com.finance.command.model.TransactionGoalAllocation alloc = allocationOpt.get();
            try {
                goalBudgetService.adjustGoalSavedAmount(alloc.getGoalId(), userId, alloc.getAmount().negate());
                log.info("Reverted {} from goal {} due to transaction {} deletion", alloc.getAmount(), alloc.getGoalId(), id);
            } catch (Exception e) {
                log.warn("Failed to revert goal allocation for goal {}: {}", alloc.getGoalId(), e.getMessage());
            }
        }
        
        entry.setDeletedAt(LocalDateTime.now());
        repository.save(entry);
        
        OutboxEvent event = new OutboxEvent();
        event.setUserId(userId);
        event.setEventType("DELETE");
        event.setEntityId(id);
        outboxEventRepository.save(event);
        
        deleteCounter.increment();
        queryCacheEvictor.evictUserKeysWithReplicationGuard(userId);
        
        log.info("Transaction deleted: id={}, user={}", id, userId);
    }

    // ── Read — single entry & goal contributions ──────────────────────────────

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<CreateEntryResponse> getGoalContributions(Long goalId, UUID userId, int page, int size) {
        return repository.findByGoalIdAndUserId(goalId, userId, org.springframework.data.domain.PageRequest.of(page, size))
                .map(this::convertToResponse);
    }

    @Transactional(readOnly = true)
    public CreateEntryResponse getEntryById(Long id, UUID userId) {
        TransactionEntry entry = repository.findByIdAndUserIdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Transaction entry with ID " + id + " not found"));
        return convertToResponse(entry);
    }

    // ── Read — paginated list with optional date range ────────────────────────

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

    // ── Read — search ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    @Cacheable(value = "transactions", key = "#userId + ':search:' + #query + ':' + #page + ':' + #size", sync = true)
    public Page<TransactionEntry> searchEntries(UUID userId, String query, int page, int size) {
        return repository.searchByUserId(userId, query, PageRequest.of(page, size));
    }

    // ── Read — summary ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    @Cacheable(value = "transactions", key = "#userId + ':summary'", sync = true)
    public Map<String, Object> getSummary(UUID userId) {
        // Two SQL SUM aggregates + one COUNT — no full table scan needed.
        BigDecimal income  = safe(repository.sumByType(userId, TransactionType.INCOME));
        BigDecimal expense = safe(repository.sumByType(userId, TransactionType.EXPENSE));
        long count         = repository.countActiveByUser(userId);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalIncome",  income);
        summary.put("totalExpense", expense);
        summary.put("netBalance",   income.subtract(expense));
        summary.put("totalCount",   count);
        return summary;
    }

    // ── Read — recurring transactions ─────────────────────────────────────────

    @Transactional(readOnly = true)
    @Cacheable(value = "transactions", key = "#userId + ':recurring'", sync = true)
    public List<TransactionEntry> getRecurringEntries(UUID userId) {
        return repository.findByUserIdAndRecurringTrueAndDeletedAtIsNull(userId);
    }

    // ── CSV export ────────────────────────────────────────────────────────────

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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private BigDecimal safe(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private CreateEntryResponse convertToResponse(TransactionEntry entry) {
        return new CreateEntryResponse(
                entry.getId(), entry.getUserId(), entry.getName(), entry.getAmount(),
                entry.getType(), entry.getCategory(),
                entry.getCurrency(), entry.getDescription(),
                entry.isRecurring(), entry.getRecurringPeriod(),
                entry.getCreatedAt(), entry.getUpdatedAt(),
                entry.getReceiptUrl()
        );
    }
}