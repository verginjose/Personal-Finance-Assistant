package com.upsertservice.service;

import com.upsertservice.dto.CreateEntryRequest;
import com.upsertservice.dto.CreateEntryResponse;
import com.upsertservice.dto.UpdateEntryRequest;
import com.upsertservice.model.TransactionEntry;
import com.upsertservice.model.TransactionType;
import com.upsertservice.repository.TransactionEntryRepository;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class TransactionEntryService {

    private final TransactionEntryRepository repository;

    public CreateEntryResponse createEntry(CreateEntryRequest request) {
        TransactionEntry entry = new TransactionEntry(
                request.getUserId(), request.getName(),
                request.getAmount(), request.getType(), request.getCurrency()
        );
        if (request.getType() == TransactionType.EXPENSE) {
            entry.setExpenseCategory(request.getExpenseCategory());
        } else {
            entry.setIncomeCategory(request.getIncomeCategory());
        }
        entry.setDescription(request.getDescription());
        TransactionEntry saved = repository.save(entry);
        log.info("Transaction created: id={}, user={}", saved.getId(), saved.getUserId());
        return convertToResponse(saved);
    }

    public CreateEntryResponse updateEntry(UpdateEntryRequest request) {
        TransactionEntry existing = repository.findById(request.getId())
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
        existing.setExpenseCategory(null);
        existing.setIncomeCategory(null);
        if (request.getType() == TransactionType.EXPENSE) {
            existing.setExpenseCategory(request.getExpenseCategory());
        } else {
            existing.setIncomeCategory(request.getIncomeCategory());
        }
        TransactionEntry updated = repository.save(existing);
        log.info("Transaction updated: id={}", updated.getId());
        return convertToResponse(updated);
    }

    public void deleteEntry(Long id, UUID userId) {
        TransactionEntry entry = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Transaction entry with ID " + id + " not found"));
        if (!entry.getUserId().equals(userId)) {
            throw new SecurityException("User is not authorized to delete this transaction");
        }
        repository.delete(entry);
        log.info("Transaction deleted: id={}, user={}", id, userId);
    }

    @Transactional(readOnly = true)
    public Page<TransactionEntry> getEntriesByUserId(UUID userId, TransactionType type, int page, int size) {
        PageRequest pr = PageRequest.of(page, size);
        if (type != null) {
            return repository.findByUserIdAndTypeOrderByCreatedAtDesc(userId, type, pr);
        }
        return repository.findByUserIdOrderByCreatedAtDesc(userId, pr);
    }

    @Transactional(readOnly = true)
    public Page<TransactionEntry> searchEntries(UUID userId, String query, int page, int size) {
        return repository.searchByUserId(userId, query, PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getSummary(UUID userId) {
        List<TransactionEntry> all = repository.findByUserIdOrderByCreatedAtDesc(userId);
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

    private CreateEntryResponse convertToResponse(TransactionEntry entry) {
        return new CreateEntryResponse(
                entry.getId(), entry.getUserId(), entry.getName(), entry.getAmount(),
                entry.getType(), entry.getExpenseCategory(), entry.getIncomeCategory(),
                entry.getCurrency(), entry.getDescription(),
                entry.getCreatedAt(), entry.getUpdatedAt()
        );
    }
}