package com.upsertservice.service;

import com.upsertservice.dto.CreateEntryRequest;
import com.upsertservice.dto.CreateEntryResponse;
import com.upsertservice.dto.UpdateEntryRequest;
import com.upsertservice.model.ExpenseCategory;
import com.upsertservice.model.IncomeCategory;
import com.upsertservice.model.TransactionEntry;
import com.upsertservice.model.TransactionType;
import com.upsertservice.repository.TransactionEntryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@Transactional
public class TransactionEntryService {

    private final TransactionEntryRepository repository;

    @Autowired
    public TransactionEntryService(TransactionEntryRepository repository) {
        this.repository = repository;
    }

    public CreateEntryResponse createEntry(CreateEntryRequest request) {
        // Validate category consistency
        validateCategoryConsistency(request.getType(),
                request.getExpenseCategory(), request.getIncomeCategory());

        // Create new transaction entry
        TransactionEntry entry = new TransactionEntry(
                request.getUserId(),
                request.getName(),
                request.getAmount(),
                request.getType(),
                request.getCurrency()
        );

        // Set appropriate category based on type
        if (request.getType() == TransactionType.EXPENSE) {
            entry.setExpenseCategory(request.getExpenseCategory());
        } else {
            entry.setIncomeCategory(request.getIncomeCategory());
        }

        entry.setDescription(request.getDescription());

        // Save to database
        TransactionEntry savedEntry = repository.save(entry);

        // Convert to response DTO

        return convertToResponse(savedEntry);
    }

    public CreateEntryResponse updateEntry(UpdateEntryRequest request) {
        // Validate category consistency
        validateCategoryConsistency(request.getType(),
                request.getExpenseCategory(), request.getIncomeCategory());

        // Find existing entry
        Optional<TransactionEntry> existingEntryOpt = repository.findById(request.getId());
        if (existingEntryOpt.isEmpty()) {
            throw new IllegalArgumentException("Transaction entry with ID " + request.getId() + " not found");
        }

        TransactionEntry existingEntry = existingEntryOpt.get();

        // Verify user ownership
        if (!existingEntry.getUserId().equals(request.getUserId())) {
            throw new IllegalArgumentException("User is not authorized to update this transaction");
        }

        // Update fields
        existingEntry.setName(request.getName());
        existingEntry.setAmount(request.getAmount());
        existingEntry.setType(request.getType());
        existingEntry.setCurrency(request.getCurrency());
        existingEntry.setDescription(request.getDescription());

        // Clear both categories first, then set the appropriate one
        existingEntry.setExpenseCategory(null);
        existingEntry.setIncomeCategory(null);

        if (request.getType() == TransactionType.EXPENSE) {
            existingEntry.setExpenseCategory(request.getExpenseCategory());
        } else {
            existingEntry.setIncomeCategory(request.getIncomeCategory());
        }

        // Save updated entry
        TransactionEntry updatedEntry = repository.save(existingEntry);

        // Convert to response DTO
        return convertToResponse(updatedEntry);
    }

    private void validateCategoryConsistency(TransactionType type,
                                             ExpenseCategory expenseCategory,
                                             IncomeCategory incomeCategory) {

        if (type == TransactionType.EXPENSE) {
            if (expenseCategory == null) {
                throw new IllegalArgumentException("Expense category is required for expense transactions");
            }
            if (incomeCategory != null) {
                throw new IllegalArgumentException("Income category should not be provided for expense transactions");
            }
        } else if (type == TransactionType.INCOME) {
            if (incomeCategory == null) {
                throw new IllegalArgumentException("Income category is required for income transactions");
            }
            if (expenseCategory != null) {
                throw new IllegalArgumentException("Expense category should not be provided for income transactions");
            }
        }
    }

    private CreateEntryResponse convertToResponse(TransactionEntry entry) {
        return new CreateEntryResponse(
                entry.getId(),
                entry.getUserId(),
                entry.getName(),
                entry.getAmount(),
                entry.getType(),
                entry.getExpenseCategory(),
                entry.getIncomeCategory(),
                entry.getCurrency(),
                entry.getDescription(),
                entry.getCreatedAt(),
                entry.getUpdatedAt()
        );
    }
}