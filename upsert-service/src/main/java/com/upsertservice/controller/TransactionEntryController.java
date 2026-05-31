package com.upsertservice.controller;

import com.upsertservice.dto.CreateEntryRequest;
import com.upsertservice.dto.CreateEntryResponse;
import com.upsertservice.dto.PatchAmountRequest;
import com.upsertservice.dto.UpdateEntryRequest;
import com.upsertservice.model.TransactionEntry;
import com.upsertservice.model.TransactionType;
import com.upsertservice.service.TransactionEntryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
@Tag(name = "Transaction Entry", description = "APIs for managing financial entries")
public class TransactionEntryController {

    private final TransactionEntryService service;

    // ── Create ────────────────────────────────────────────────────────────────

    @PostMapping("/create")
    @Operation(summary = "Create a new financial entry")
    public ResponseEntity<CreateEntryResponse> createEntry(
            @RequestHeader("X-User-Id") String xUserId,
            @Valid @RequestBody CreateEntryRequest request,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        if (!request.getUserId().toString().equalsIgnoreCase(xUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: User ID mismatch");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createEntry(request, idempotencyKey));
    }

    // ── Update (full) ─────────────────────────────────────────────────────────

    @PutMapping("/update")
    @Operation(summary = "Update an existing financial entry (all fields)")
    public ResponseEntity<CreateEntryResponse> updateEntry(
            @RequestHeader("X-User-Id") String xUserId,
            @Valid @RequestBody UpdateEntryRequest request) {
        if (!request.getUserId().toString().equalsIgnoreCase(xUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: User ID mismatch");
        }
        return ResponseEntity.ok(service.updateEntry(request));
    }

    // ── Patch amount (partial) ────────────────────────────────────────────────

    @PatchMapping("/entries/{id}/amount")
    @Operation(summary = "Correct the amount of an entry without resending all fields")
    public ResponseEntity<CreateEntryResponse> patchAmount(
            @PathVariable Long id,
            @RequestParam UUID userId,
            @Valid @RequestBody PatchAmountRequest body) {
        return ResponseEntity.ok(service.patchAmount(id, userId, body.amount()));
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @DeleteMapping("/delete/{id}")
    @Operation(summary = "Soft-delete a financial entry")
    public ResponseEntity<Map<String, String>> deleteEntry(
            @PathVariable Long id,
            @RequestParam UUID userId) {
        service.deleteEntry(id, userId);
        return ResponseEntity.ok(Map.of("message", "Entry deleted successfully"));
    }

    @PostMapping("/delete/bulk")
    @Operation(summary = "Bulk soft-delete financial entries")
    public ResponseEntity<Map<String, Object>> bulkDelete(
            @RequestParam UUID userId,
            @RequestBody List<Long> ids) {
        ids.forEach(id -> service.deleteEntry(id, userId));
        return ResponseEntity.ok(Map.of("message", "Entries deleted successfully", "count", ids.size()));
    }

    // ── Read — single entry ───────────────────────────────────────────────────

    @GetMapping("/entries/{id}")
    @Operation(summary = "Get a single entry by ID")
    public ResponseEntity<CreateEntryResponse> getEntryById(
            @PathVariable Long id,
            @RequestParam UUID userId) {
        return ResponseEntity.ok(service.getEntryById(id, userId));
    }

    // ── Read — paginated list with optional date-range filter ─────────────────

    @GetMapping("/entries")
    @Operation(summary = "Get paginated entries for a user with optional type and date-range filters")
    public ResponseEntity<Page<TransactionEntry>> getEntries(
            @RequestParam UUID userId,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.getEntriesByUserId(userId, type, startDate, endDate, page, size));
    }

    // ── Read — search ─────────────────────────────────────────────────────────

    @GetMapping("/search")
    @Operation(summary = "Full-text search transactions by name or description")
    public ResponseEntity<Page<TransactionEntry>> searchEntries(
            @RequestParam UUID userId,
            @RequestParam String q,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.searchEntries(userId, q, page, size));
    }

    // ── Read — summary ────────────────────────────────────────────────────────

    @GetMapping("/summary")
    @Operation(summary = "Get financial summary (total income, expense, net balance)")
    public ResponseEntity<Map<String, Object>> getSummary(@RequestParam UUID userId) {
        return ResponseEntity.ok(service.getSummary(userId));
    }

    // ── Read — recurring entries ──────────────────────────────────────────────

    @GetMapping("/recurring")
    @Operation(summary = "List all recurring transactions for a user")
    public ResponseEntity<List<TransactionEntry>> getRecurringEntries(@RequestParam UUID userId) {
        return ResponseEntity.ok(service.getRecurringEntries(userId));
    }

    // ── Export ────────────────────────────────────────────────────────────────

    @GetMapping("/entries/export")
    @Operation(summary = "Export all entries as CSV file")
    public ResponseEntity<String> exportCsv(@RequestParam UUID userId) {
        String csv = service.exportCsv(userId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"transactions.csv\"")
                .body(csv);
    }

    // ── Health ────────────────────────────────────────────────────────────────

    @GetMapping("/health")
    @Operation(summary = "Health check")
    public ResponseEntity<Map<String, String>> healthCheck() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "upsert-service"));
    }
}
