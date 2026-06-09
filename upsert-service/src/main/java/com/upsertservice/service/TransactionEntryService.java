package com.upsertservice.service;

import com.upsertservice.dto.CreateEntryRequest;
import com.upsertservice.dto.CreateEntryResponse;
import com.upsertservice.dto.UpdateEntryRequest;
import com.upsertservice.events.CacheEvictPublisher;
import com.upsertservice.model.IdempotencyRecord;
import com.upsertservice.model.TransactionEntry;
import com.upsertservice.model.TransactionType;
import com.upsertservice.repository.IdempotencyRecordRepository;
import com.upsertservice.repository.TransactionEntryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.CacheEvict;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@Transactional
public class TransactionEntryService {

    private final TransactionEntryRepository repository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final Counter createCounter;
    private final Counter deleteCounter;
    private final CacheEvictPublisher cacheEvictPublisher;
    public TransactionEntryService(
            TransactionEntryRepository repository,
            IdempotencyRecordRepository idempotencyRecordRepository,
            MeterRegistry meterRegistry, CacheEvictPublisher cacheEvictPublisher
    ) {
        this.repository = repository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.createCounter = meterRegistry.counter("transactions.create.success");
        this.deleteCounter = meterRegistry.counter("transactions.delete.success");
        this.cacheEvictPublisher = cacheEvictPublisher;
    }

    // ── Create ────────────────────────────────────────────────────────────────

    public CreateEntryResponse createEntry(CreateEntryRequest request, String idempotencyKey) {
        return createEntry(request, idempotencyKey, true);
    }

    public CreateEntryResponse createEntry(CreateEntryRequest request, String idempotencyKey, boolean publishCacheEvict) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            IdempotencyRecord existing = idempotencyRecordRepository
                    .findByUserIdAndIdempotencyKey(request.getUserId(), idempotencyKey.trim())
                    .orElse(null);
            log.info("Trying to find the IdempotencyRecord for this key {}",idempotencyKey);
            if (existing != null && existing.getTransactionId() != null) {
                log.info("Found existing IdempotencyRecord for this key {}",existing.getId());
                TransactionEntry existingEntry = repository.findByIdAndDeletedAtIsNull(existing.getTransactionId())
                        .orElseThrow(() -> new IllegalStateException("Idempotency key points to a missing transaction"));
                return convertToResponse(existingEntry);
            }
            log.info("Creating the IdempotencyRecord for this key {}",request.getUserId());
        }

        TransactionEntry entry = getTransactionEntry(request);

        TransactionEntry saved = repository.save(entry);
        if (publishCacheEvict) {
            cacheEvictPublisher.publish(request.getUserId(), "CREATE", saved.getId());
        }
        createCounter.increment();
        log.info("Idempotency key {}",idempotencyKey);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            IdempotencyRecord record = new IdempotencyRecord();
            record.setUserId(request.getUserId());
            record.setIdempotencyKey(idempotencyKey.trim());
            record.setTransactionId(saved.getId());
            record=idempotencyRecordRepository.save(record);
            log.info("Created IdempotencyRecord for this key {}",record.getId());
        }

        log.info("Transaction created: id={}, user={}, recurring={}", saved.getId(), saved.getUserId(), saved.isRecurring());
        return convertToResponse(saved);
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
        return entry;
    }

    // ── Update (full) ─────────────────────────────────────────────────────────

    public CreateEntryResponse updateEntry(UpdateEntryRequest request) {
        TransactionEntry existing = repository.findByIdAndDeletedAtIsNull(request.getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Transaction entry with ID " + request.getId() + " not found"));
        if (!existing.getUserId().equals(request.getUserId())) {
            throw new SecurityException("User is not authorized to update this transaction");
        }
        existing.setName(request.getName());
        existing.setAmount(request.getAmount());
        existing.setType(request.getType());
        existing.setCurrency(request.getCurrency());
        existing.setDescription(request.getDescription());
        existing.setCategory(request.getCategory());
        existing.setRecurring(request.isRecurring());
        existing.setRecurringPeriod(request.getRecurringPeriod());
        TransactionEntry updated = repository.save(existing);
        cacheEvictPublisher.publish(request.getUserId(), "UPDATE", updated.getId()); // ADD THIS
        log.info("Transaction updated: id={}", updated.getId());
        return convertToResponse(updated);
    }

    // ── Patch amount (partial update) ─────────────────────────────────────────

    public CreateEntryResponse patchAmount(Long id, UUID userId, BigDecimal newAmount) {
        TransactionEntry entry = repository.findByIdAndUserIdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Transaction entry with ID " + id + " not found for user " + userId));
        entry.setAmount(newAmount);
        TransactionEntry saved = repository.save(entry);
        cacheEvictPublisher.publish(userId, "PATCH", id); // ADD THIS
        log.info("Transaction amount patched: id={}, newAmount={}", id, newAmount);
        return convertToResponse(saved);
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    public void deleteEntry(Long id, UUID userId) {
        TransactionEntry entry = repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Transaction entry with ID " + id + " not found"));
        if (!entry.getUserId().equals(userId)) {
            throw new SecurityException("User is not authorized to delete this transaction");
        }
        entry.setDeletedAt(LocalDateTime.now());
        repository.save(entry);
        cacheEvictPublisher.publish(userId, "DELETE", id); // ADD THIS
        deleteCounter.increment();
        log.info("Transaction deleted: id={}, user={}", id, userId);
    }

    // ── Read — single entry ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public CreateEntryResponse getEntryById(Long id, UUID userId) {
        TransactionEntry entry = repository.findByIdAndUserIdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Transaction entry with ID " + id + " not found"));
        return convertToResponse(entry);
    }

    // ── Read — paginated list with optional date range ────────────────────────

    @Transactional(readOnly = true)
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
    public Page<TransactionEntry> searchEntries(UUID userId, String query, int page, int size) {
        return repository.searchByUserId(userId, query, PageRequest.of(page, size));
    }

    // ── Read — summary ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
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
                entry.getCreatedAt(), entry.getUpdatedAt()
        );
    }
}