package com.upsertservice.controller;

import com.upsertservice.dto.*;
import com.upsertservice.model.*;
import com.upsertservice.service.SplitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/upsert/groups")
@RequiredArgsConstructor
public class SplitController {

    private final SplitService splitService;

    /* ── GROUPS ── */

    @PostMapping
    public ResponseEntity<ExpenseGroup> createGroup(@Valid @RequestBody CreateGroupRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(splitService.createGroup(req));
    }

    @GetMapping
    public ResponseEntity<List<ExpenseGroup>> getUserGroups(@RequestParam UUID userId) {
        return ResponseEntity.ok(splitService.getUserGroups(userId));
    }

    @GetMapping("/{groupId}")
    public ResponseEntity<ExpenseGroup> getGroup(@PathVariable Long groupId) {
        return splitService.getGroup(groupId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /* ── MEMBERS ── */

    @PostMapping("/{groupId}/members")
    public ResponseEntity<GroupMember> addMember(
            @PathVariable Long groupId,
            @Valid @RequestBody AddMemberRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(splitService.addMember(groupId, req));
    }

    @GetMapping("/{groupId}/members")
    public ResponseEntity<List<GroupMember>> getMembers(@PathVariable Long groupId) {
        return ResponseEntity.ok(splitService.getGroupMembers(groupId));
    }

    /* ── EXPENSES ── */

    @PostMapping("/{groupId}/expenses")
    public ResponseEntity<SharedExpense> addExpense(
            @PathVariable Long groupId,
            @Valid @RequestBody CreateSharedExpenseRequest req) {
        req.setGroupId(groupId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(splitService.addSharedExpense(req));
    }

    @GetMapping("/{groupId}/expenses")
    public ResponseEntity<List<SharedExpense>> getExpenses(@PathVariable Long groupId) {
        return ResponseEntity.ok(splitService.getGroupExpenses(groupId));
    }

    /* ── BALANCES ── */

    @GetMapping("/{groupId}/balances")
    public ResponseEntity<GroupBalanceResponse> getBalances(@PathVariable Long groupId) {
        return ResponseEntity.ok(splitService.getGroupBalances(groupId));
    }

    /* ── SETTLEMENT ── */

    @PostMapping("/{groupId}/settle")
    public ResponseEntity<Map<String, String>> settleDebt(
            @PathVariable Long groupId,
            @RequestParam UUID fromUserId,
            @RequestParam UUID toUserId) {
        splitService.settleDebt(groupId, fromUserId, toUserId);
        return ResponseEntity.ok(Map.of("message", "Settlement recorded successfully"));
    }
}
