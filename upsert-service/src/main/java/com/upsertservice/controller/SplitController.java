package com.upsertservice.controller;

import com.upsertservice.dto.*;
import com.upsertservice.model.*;
import com.upsertservice.service.SplitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/upsert/groups")
@RequiredArgsConstructor
public class SplitController {

    private final SplitService splitService;

    private static UUID requireUserId(String xUserId) {
        try {
            return UUID.fromString(xUserId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid X-User-Id header");
        }
    }

    /* ── GROUPS ── */

    @PostMapping
    public ResponseEntity<ExpenseGroup> createGroup(
            @RequestHeader("X-User-Id") String xUserId,
            @Valid @RequestBody CreateGroupRequest req) {
        UUID actorId = requireUserId(xUserId);
        if (!req.getCreatedBy().equals(actorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: User ID mismatch");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(splitService.createGroup(req));
    }

    @PutMapping("/{groupId}")
    public ResponseEntity<ExpenseGroup> updateGroup(
            @RequestHeader("X-User-Id") String xUserId,
            @PathVariable Long groupId,
            @Valid @RequestBody UpdateGroupRequest req) {
        return ResponseEntity.ok(splitService.updateGroup(groupId, req, requireUserId(xUserId)));
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
            @RequestHeader("X-User-Id") String xUserId,
            @PathVariable Long groupId,
            @Valid @RequestBody AddMemberRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(splitService.addMember(groupId, req, requireUserId(xUserId)));
    }

    @PostMapping("/{groupId}/members/{memberUserId}/accept")
    public ResponseEntity<Map<String, String>> acceptInvitation(
            @RequestHeader("X-User-Id") String xUserId,
            @PathVariable Long groupId,
            @PathVariable UUID memberUserId) {
        if (!requireUserId(xUserId).equals(memberUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot accept invitation for another user");
        }
        splitService.acceptInvitation(groupId, memberUserId);
        return ResponseEntity.ok(Map.of("message", "Invitation accepted"));
    }

    @PostMapping("/{groupId}/members/{memberUserId}/reject")
    public ResponseEntity<Map<String, String>> rejectInvitation(
            @RequestHeader("X-User-Id") String xUserId,
            @PathVariable Long groupId,
            @PathVariable UUID memberUserId) {
        if (!requireUserId(xUserId).equals(memberUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot reject invitation for another user");
        }
        splitService.rejectInvitation(groupId, memberUserId);
        return ResponseEntity.ok(Map.of("message", "Invitation rejected"));
    }

    @DeleteMapping("/{groupId}/members/{memberUserId}")
    public ResponseEntity<Map<String, String>> leaveGroup(
            @RequestHeader("X-User-Id") String xUserId,
            @PathVariable Long groupId,
            @PathVariable UUID memberUserId) {
        if (!requireUserId(xUserId).equals(memberUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot remove another user");
        }
        splitService.leaveGroup(groupId, memberUserId);
        return ResponseEntity.ok(Map.of("message", "Successfully left the group"));
    }

    @GetMapping("/{groupId}/members")
    public ResponseEntity<List<GroupMember>> getMembers(@PathVariable Long groupId) {
        return ResponseEntity.ok(splitService.getGroupMembers(groupId));
    }

    /* ── EXPENSES ── */

    @PostMapping("/{groupId}/expenses")
    public ResponseEntity<SharedExpense> addExpense(
            @RequestHeader("X-User-Id") String xUserId,
            @PathVariable Long groupId,
            @Valid @RequestBody CreateSharedExpenseRequest req) {
        req.setGroupId(groupId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(splitService.addSharedExpense(req, requireUserId(xUserId)));
    }

    @GetMapping("/{groupId}/expenses")
    public ResponseEntity<List<SharedExpense>> getExpenses(@PathVariable Long groupId) {
        return ResponseEntity.ok(splitService.getGroupExpenses(groupId));
    }

    @DeleteMapping("/{groupId}/expenses/{expenseId}")
    public ResponseEntity<Map<String, String>> deleteExpense(
            @RequestHeader("X-User-Id") String xUserId,
            @PathVariable Long groupId,
            @PathVariable Long expenseId) {
        splitService.deleteSharedExpense(groupId, expenseId, requireUserId(xUserId));
        return ResponseEntity.ok(Map.of("message", "Expense deleted successfully"));
    }

    /* ── ACTIVITY ── */

    @GetMapping("/{groupId}/activity")
    public ResponseEntity<List<GroupActivity>> getActivity(@PathVariable Long groupId) {
        return ResponseEntity.ok(splitService.getGroupActivity(groupId));
    }

    /* ── BALANCES ── */

    @GetMapping("/{groupId}/balances")
    public ResponseEntity<GroupBalanceResponse> getBalances(@PathVariable Long groupId) {
        return ResponseEntity.ok(splitService.getGroupBalances(groupId));
    }

    /* ── SETTLEMENT ── */

    @PostMapping("/{groupId}/settle")
    public ResponseEntity<Map<String, String>> settleDebt(
            @RequestHeader("X-User-Id") String xUserId,
            @PathVariable Long groupId,
            @RequestParam UUID fromUserId,
            @RequestParam UUID toUserId) {
        splitService.settleDebt(groupId, fromUserId, toUserId, requireUserId(xUserId));
        return ResponseEntity.ok(Map.of("message", "Settlement recorded successfully"));
    }
}
