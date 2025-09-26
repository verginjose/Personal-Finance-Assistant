package com.upsertservice.controller;

import com.upsertservice.dto.CreateEntryRequest;
import com.upsertservice.dto.CreateEntryResponse;
import com.upsertservice.dto.ErrorResponse;
import com.upsertservice.dto.UpdateEntryRequest;
import com.upsertservice.service.TransactionEntryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/upsert")
@Tag(name = "Transaction Entry", description = "APIs for creating financial entries")
public class TransactionEntryController {

        private final TransactionEntryService service;

        @Autowired
        public TransactionEntryController(TransactionEntryService service) {
                this.service = service;
        }

        @PostMapping("/create")
        @Operation(summary = "Create a new financial entry", description = "Creates a new income or expense entry")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "201", description = "Entry created successfully"),
                        @ApiResponse(responseCode = "400", description = "Invalid input data"),
                        @ApiResponse(responseCode = "500", description = "Internal server error")
        })
        public ResponseEntity<?> createEntry(@Valid @RequestBody CreateEntryRequest request,
                        BindingResult bindingResult) {

                // Check for validation errors
                if (bindingResult.hasErrors()) {
                        List<String> errors = bindingResult.getFieldErrors().stream()
                                        .map(error -> error.getField() + ": " + error.getDefaultMessage())
                                        .collect(Collectors.toList());

                        ErrorResponse errorResponse = new ErrorResponse(
                                        "Validation failed", errors, HttpStatus.BAD_REQUEST.value());
                        return ResponseEntity.badRequest().body(errorResponse);
                }

                try {
                        CreateEntryResponse response = service.createEntry(request);
                        return ResponseEntity.status(HttpStatus.CREATED).body(response);
                } catch (Exception e) {
                        ErrorResponse errorResponse = new ErrorResponse(
                                        "Failed to create entry",
                                        List.of(e.getMessage()),
                                        HttpStatus.INTERNAL_SERVER_ERROR.value());
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                        .body(errorResponse);
                }
        }

        @PutMapping("/update")
        @Operation(summary = "Update an existing financial entry", description = "Updates an existing income or expense entry")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "200", description = "Entry updated successfully"),
                        @ApiResponse(responseCode = "400", description = "Invalid input data"),
                        @ApiResponse(responseCode = "404", description = "Entry not found"),
                        @ApiResponse(responseCode = "500", description = "Internal server error")
        })
        public ResponseEntity<?> updateEntry(@Valid @RequestBody UpdateEntryRequest request,
                        BindingResult bindingResult) {

                // Check for validation errors
                if (bindingResult.hasErrors()) {
                        List<String> errors = bindingResult.getFieldErrors().stream()
                                        .map(error -> error.getField() + ": " + error.getDefaultMessage())
                                        .collect(Collectors.toList());

                        ErrorResponse errorResponse = new ErrorResponse(
                                        "Validation failed", errors, HttpStatus.BAD_REQUEST.value());
                        return ResponseEntity.badRequest().body(errorResponse);
                }

                try {
                        CreateEntryResponse response = service.updateEntry(request);
                        return ResponseEntity.ok(response);
                } catch (IllegalArgumentException e) {
                        ErrorResponse errorResponse = new ErrorResponse(
                                        "Entry not found or invalid update",
                                        List.of(e.getMessage()),
                                        HttpStatus.NOT_FOUND.value());
                        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                        .body(errorResponse);
                } catch (Exception e) {
                        ErrorResponse errorResponse = new ErrorResponse(
                                        "Failed to update entry",
                                        List.of(e.getMessage()),
                                        HttpStatus.INTERNAL_SERVER_ERROR.value());
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                        .body(errorResponse);
                }
        }

        @GetMapping("/health")
        @Operation(summary = "Health check", description = "Check if the service is running")
        public ResponseEntity<String> healthCheck() {
                return ResponseEntity.ok("Create Entry Service is running");
        }
}
