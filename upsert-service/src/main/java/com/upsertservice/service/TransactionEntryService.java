package com.upsertservice.service;

import com.upsertservice.dto.CreateEntryRequest;
import com.upsertservice.dto.CreateEntryResponse;
import com.upsertservice.dto.UpdateEntryRequest;
import com.upsertservice.model.TransactionEntry;
import com.upsertservice.model.TransactionType;
import com.upsertservice.repository.TransactionEntryRepository;
import com.upsertservice.repository.OutboxEventRepository;
import com.upsertservice.model.OutboxEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.Cacheable;
import com.upsertservice.cache.CacheKeyRegistry;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;

@Slf4j
@Service
@Transactional
public class TransactionEntryService {

    private final TransactionEntryRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final Counter createCounter;
    private final Counter deleteCounter;
    private final OutboxEventRepository outboxEventRepository;
    private final GoalBudgetService goalBudgetService;
    private final com.upsertservice.repository.TransactionGoalAllocationRepository allocationRepository;
    private final CacheKeyRegistry cacheKeyRegistry;
    private final com.upsertservice.repository.ExpenseTransactionLinkRepository transactionLinkRepo;

    public TransactionEntryService(
            TransactionEntryRepository repository,
            StringRedisTemplate redisTemplate,
            MeterRegistry meterRegistry, OutboxEventRepository outboxEventRepository,
            GoalBudgetService goalBudgetService,
            com.upsertservice.repository.TransactionGoalAllocationRepository allocationRepository,
            CacheKeyRegistry cacheKeyRegistry,
            com.upsertservice.repository.ExpenseTransactionLinkRepository transactionLinkRepo
    ) {
        this.repository = repository;
        this.redisTemplate = redisTemplate;
        this.createCounter = meterRegistry.counter("transactions.create.success");
        this.deleteCounter = meterRegistry.counter("transactions.delete.success");
        this.outboxEventRepository = outboxEventRepository;
        this.goalBudgetService = goalBudgetService;
        this.allocationRepository = allocationRepository;
        this.cacheKeyRegistry = cacheKeyRegistry;
        this.transactionLinkRepo = transactionLinkRepo;
    }

    // ── Create ────────────────────────────────────────────────────────────────

    public CreateEntryResponse createEntry(CreateEntryRequest request, String idempotencyKey) {
        return createEntry(request, idempotencyKey, true);
    }

    public CreateEntryResponse createEntry(CreateEntryRequest request, String idempotencyKey, boolean publishCacheEvict) {
        String redisKey = null;
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            redisKey = "idem:txn:" + request.getUserId() + ":" + idempotencyKey.trim();
            String existingTxnIdStr = redisTemplate.opsForValue().get(redisKey);
            log.info("Trying to find the IdempotencyRecord for this key {}",idempotencyKey);
            if (existingTxnIdStr != null) {
                log.info("Found existing IdempotencyRecord for this key {}", idempotencyKey);
                try {
                    Long existingTxnId = Long.parseLong(existingTxnIdStr);
                    TransactionEntry existingEntry = repository.findByIdAndDeletedAtIsNull(existingTxnId)
                            .orElseThrow(() -> new IllegalStateException("Idempotency key points to a missing transaction"));
                    return convertToResponse(existingEntry);
                } catch (NumberFormatException e) {
                    log.error("Invalid transaction ID in redis cache", e);
                }
            }
            log.info("Creating the IdempotencyRecord for this key {}",request.getUserId());
        }

        TransactionEntry entry = getTransactionEntry(request);

        TransactionEntry saved = repository.save(entry);
        
        if (publishCacheEvict) {
            OutboxEvent event = new OutboxEvent();
            event.setUserId(request.getUserId());
            event.setEventType("CREATE");
            event.setEntityId(saved.getId());
            outboxEventRepository.save(event);
        }
        createCounter.increment();
        cacheKeyRegistry.evictForUser(request.getUserId());
        
        log.info("Idempotency key {}",idempotencyKey);
        if (redisKey != null) {
            redisTemplate.opsForValue().set(redisKey, String.valueOf(saved.getId()), Duration.ofHours(24));
            log.info("Created IdempotencyRecord for this key {} in Redis", idempotencyKey);
        }

        log.info("Transaction created: id={}, user={}, recurring={}", saved.getId(), saved.getUserId(), saved.isRecurring());
        return convertToResponse(saved);
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
                .forEach(cacheKeyRegistry::evictForUser);

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
        return entry;
    }

    public static LocalDateTime calculateNextRunDate(LocalDateTime from, com.upsertservice.model.RecurringPeriod period) {
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
        
        TransactionEntry updated = repository.save(existing);
        
        if (diff.compareTo(BigDecimal.ZERO) != 0) {
            java.util.Optional<com.upsertservice.model.TransactionGoalAllocation> allocationOpt = allocationRepository.findByTransactionId(updated.getId());
            if (allocationOpt.isPresent()) {
                com.upsertservice.model.TransactionGoalAllocation alloc = allocationOpt.get();
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
        
        cacheKeyRegistry.evictForUser(request.getUserId());
        
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
            java.util.Optional<com.upsertservice.model.TransactionGoalAllocation> allocationOpt = allocationRepository.findByTransactionId(saved.getId());
            if (allocationOpt.isPresent()) {
                com.upsertservice.model.TransactionGoalAllocation alloc = allocationOpt.get();
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
        
        cacheKeyRegistry.evictForUser(userId);
        
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
        java.util.Optional<com.upsertservice.model.TransactionGoalAllocation> allocationOpt = allocationRepository.findByTransactionId(id);
        if (allocationOpt.isPresent()) {
            com.upsertservice.model.TransactionGoalAllocation alloc = allocationOpt.get();
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
        cacheKeyRegistry.evictForUser(userId);
        
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

    private CreateEntryResponse convertToResponse(TransactionEntry entry) {
        return new CreateEntryResponse(
                entry.getId(), entry.getUserId(), entry.getName(), entry.getAmount(),
                entry.getType(), entry.getCategory(),
                entry.getCurrency(), entry.getDescription(),
                entry.isRecurring(), entry.getRecurringPeriod(),
                entry.getCreatedAt(), entry.getUpdatedAt()
        );
    }
}