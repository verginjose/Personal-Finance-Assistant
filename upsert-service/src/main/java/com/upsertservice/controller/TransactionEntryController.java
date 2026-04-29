package com.upsertservice.controller;

import com.upsertservice.dto.CreateEntryRequest;
import com.upsertservice.dto.CreateEntryResponse;
import com.upsertservice.dto.UpdateEntryRequest;
import com.upsertservice.model.TransactionEntry;
import com.upsertservice.model.TransactionType;
import com.upsertservice.service.TransactionEntryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/upsert")
@RequiredArgsConstructor
@Tag(name = "Transaction Entry", description = "APIs for managing financial entries")
public class TransactionEntryController {

    private final TransactionEntryService service;

    @PostMapping("/create")
    @Operation(summary = "Create a new financial entry")
    public ResponseEntity<CreateEntryResponse> createEntry(
            @Valid @RequestBody CreateEntryRequest request,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createEntry(request, idempotencyKey));
    }

    @PutMapping("/update")
    @Operation(summary = "Update an existing financial entry")
    public ResponseEntity<CreateEntryResponse> updateEntry(@Valid @RequestBody UpdateEntryRequest request) {
        return ResponseEntity.ok(service.updateEntry(request));
    }

    @DeleteMapping("/delete/{id}")
    @Operation(summary = "Delete a financial entry")
    public ResponseEntity<Map<String, String>> deleteEntry(
            @PathVariable Long id,
            @RequestParam UUID userId) {
        service.deleteEntry(id, userId);
        return ResponseEntity.ok(Map.of("message", "Entry deleted successfully"));
    }

    @PostMapping("/delete/bulk")
    @Operation(summary = "Bulk delete financial entries")
    public ResponseEntity<Map<String, Object>> bulkDelete(
            @RequestParam UUID userId,
            @RequestBody List<Long> ids) {
        ids.forEach(id -> service.deleteEntry(id, userId));
        return ResponseEntity.ok(Map.of("message", "Entries deleted successfully", "count", ids.size()));
    }

    /** Paginated entries for a user, optionally filtered by type */
    @GetMapping("/entries")
    @Operation(summary = "Get paginated entries for a user")
    public ResponseEntity<Page<TransactionEntry>> getEntries(
            @RequestParam UUID userId,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.getEntriesByUserId(userId, type, page, size));
    }

    /** Full-text search on name & description */
    @GetMapping("/search")
    @Operation(summary = "Search transactions by name or description")
    public ResponseEntity<Page<TransactionEntry>> searchEntries(
            @RequestParam UUID userId,
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.searchEntries(userId, q, page, size));
    }

    /** Summary: total income, expense, net, count */
    @GetMapping("/summary")
    @Operation(summary = "Get financial summary for a user")
    public ResponseEntity<Map<String, Object>> getSummary(@RequestParam UUID userId) {
        return ResponseEntity.ok(service.getSummary(userId));
    }

    @GetMapping("/health")
    @Operation(summary = "Health check")
    public ResponseEntity<Map<String, String>> healthCheck() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "upsert-service"));
    }
}
