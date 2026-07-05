package com.finance.query.controller;

import com.finance.query.dto.*;
import com.finance.query.model.*;
import com.finance.query.service.SplitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/upsert/groups")
@RequiredArgsConstructor
@Tag(name = "Group Splitting", description = "Split expenses with friends and track balances")
public class QuerySplitController {

    private final SplitService splitService;

    private static UUID requireUserId(String xUserId) {
        try {
            return UUID.fromString(xUserId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid X-User-Id header");
        }
    }

    @GetMapping
    @Operation(summary = "Get all groups for the current user")
    public ResponseEntity<List<ExpenseGroup>> getUserGroups(@RequestHeader("X-User-Id") String xUserId) {
        return ResponseEntity.ok(splitService.getUserGroups(requireUserId(xUserId)));
    }

    @GetMapping("/{groupId}")
    @Operation(summary = "Get details of a specific group")
    public ResponseEntity<ExpenseGroup> getGroup(
            @RequestHeader("X-User-Id") String xUserId,
            @PathVariable Long groupId) {
        return splitService.getGroup(groupId, requireUserId(xUserId))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{groupId}/members")
    @Operation(summary = "Get members of a group")
    public ResponseEntity<List<GroupMember>> getGroupMembers(@PathVariable Long groupId) {
        return ResponseEntity.ok(splitService.getGroupMembers(groupId));
    }

    @GetMapping("/{groupId}/expenses")
    @Operation(summary = "Get expenses in a group")
    public ResponseEntity<org.springframework.data.domain.Page<SharedExpense>> getGroupExpenses(
            @PathVariable Long groupId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(splitService.getGroupExpenses(groupId, page, size));
    }

    @GetMapping("/{groupId}/activity")
    @Operation(summary = "Get group activity feed")
    public ResponseEntity<org.springframework.data.domain.Page<GroupActivity>> getGroupActivity(
            @PathVariable Long groupId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(splitService.getGroupActivity(groupId, page, size));
    }

    @GetMapping("/{groupId}/balances")
    @Operation(summary = "Get group balances and suggested settlements")
    public ResponseEntity<GroupBalanceResponse> getGroupBalances(@PathVariable Long groupId) {
        return ResponseEntity.ok(splitService.getGroupBalances(groupId));
    }
}
