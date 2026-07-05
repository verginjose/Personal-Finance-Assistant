package com.finance.query.controller;

import com.finance.query.dto.CreateEntryResponse;
import com.finance.query.model.TransactionEntry;
import com.finance.query.model.TransactionType;
import com.finance.query.service.QueryTransactionEntryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/upsert")
@RequiredArgsConstructor
public class QueryTransactionEntryController {

    private final QueryTransactionEntryService service;

    @GetMapping("/entries/{id}")
    public ResponseEntity<CreateEntryResponse> getEntryById(
            @PathVariable Long id,
            @RequestParam UUID userId) {
        return ResponseEntity.ok(service.getEntryById(id, userId));
    }

    @GetMapping("/goals/{id}/transactions")
    public ResponseEntity<Page<CreateEntryResponse>> getGoalTransactions(
            @RequestHeader(value = "X-User-Id", required = false) String xUserId,
            @PathVariable Long id,
            @RequestParam UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "8") int size) {
        if (xUserId != null && !userId.toString().equalsIgnoreCase(xUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: User ID mismatch");
        }
        return ResponseEntity.ok(service.getGoalContributions(id, userId, page, size));
    }

    @GetMapping("/entries")
    public ResponseEntity<Page<TransactionEntry>> getEntries(
            @RequestParam UUID userId,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.getEntriesByUserId(userId, type, startDate, endDate, page, size));
    }

    @GetMapping("/search")
    public ResponseEntity<Page<TransactionEntry>> searchEntries(
            @RequestParam UUID userId,
            @RequestParam String q,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.searchEntries(userId, q, page, size));
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary(@RequestParam UUID userId) {
        return ResponseEntity.ok(service.getSummary(userId));
    }

    @GetMapping("/recurring")
    public ResponseEntity<List<TransactionEntry>> getRecurringEntries(@RequestParam UUID userId) {
        return ResponseEntity.ok(service.getRecurringEntries(userId));
    }

    @GetMapping("/entries/export")
    public ResponseEntity<String> exportCsv(@RequestParam UUID userId) {
        String csv = service.exportCsv(userId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"transactions.csv\"")
                .body(csv);
    }
}
