package com.finance.command.controller;

import com.finance.command.repository.SharedExpenseRepository;
import com.finance.command.repository.TransactionEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/upsert/storage-access")
@RequiredArgsConstructor
public class StorageAccessController {
    private final TransactionEntryRepository transactions;
    private final SharedExpenseRepository expenses;

    @GetMapping
    public ResponseEntity<Void> canRead(@RequestHeader("X-User-Id") UUID userId,
                                        @RequestParam String url) {
        boolean allowed = ReceiptOwnership.belongsTo(url, userId)
                || transactions.existsByReceiptUrlAndUserIdAndDeletedAtIsNull(url, userId)
                || expenses.canReadReceipt(url, userId);
        return allowed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
