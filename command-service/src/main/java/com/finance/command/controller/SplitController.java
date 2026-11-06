package com.finance.command.controller;

import com.finance.command.dto.*;
import com.finance.command.model.*;
import com.finance.command.service.SplitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
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



    @DeleteMapping("/{groupId}")
    public ResponseEntity<Void> deleteGroup(
            @RequestHeader("X-User-Id") String xUserId,
            @PathVariable Long groupId) {
        splitService.deleteGroup(groupId, requireUserId(xUserId));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{groupId}/archive")
    public ResponseEntity<Void> archiveGroup(
            @RequestHeader("X-User-Id") String xUserId,
            @PathVariable Long groupId,
            @RequestParam boolean archive) {
        splitService.archiveGroup(groupId, requireUserId(xUserId), archive);
        return ResponseEntity.noContent().build();
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

    /**
     * Leave the group (self) or remove a member (admins only). Unless
     * {@code settle=false}, the member's residual balance is settled first; the
     * removal fails if a balance still remains afterwards.
     */
    @DeleteMapping("/{groupId}/members/{memberUserId}")
    public ResponseEntity<Map<String, String>> removeOrLeave(
            @RequestHeader("X-User-Id") String xUserId,
            @PathVariable Long groupId,
            @PathVariable UUID memberUserId,
            @RequestParam(defaultValue = "true") boolean settle) {
        UUID actorId = requireUserId(xUserId);
        if (actorId.equals(memberUserId)) {
            splitService.leaveGroup(groupId, memberUserId);
            return ResponseEntity.ok(Map.of("message", "Successfully left the group"));
        }
        splitService.removeMember(groupId, memberUserId, actorId, settle);
        return ResponseEntity.ok(Map.of("message", "Member removed from the group"));
    }

    /** Promote ({@code admin=true}) or demote a member. Admins only. */
    @PutMapping("/{groupId}/members/{memberUserId}/admin")
    public ResponseEntity<Map<String, String>> setMemberAdmin(
            @RequestHeader("X-User-Id") String xUserId,
            @PathVariable Long groupId,
            @PathVariable UUID memberUserId,
            @RequestParam boolean admin) {
        splitService.setAdmin(groupId, memberUserId, admin, requireUserId(xUserId));
        return ResponseEntity.ok(Map.of("message", admin ? "Member promoted to admin" : "Admin role removed"));
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



    @PutMapping("/{groupId}/expenses/{expenseId}")
    public ResponseEntity<SharedExpense> updateExpense(
            @RequestHeader("X-User-Id") String xUserId,
            @PathVariable Long groupId,
            @PathVariable Long expenseId,
            @Valid @RequestBody CreateSharedExpenseRequest req) {
        req.setGroupId(groupId);
        return ResponseEntity.ok(
                splitService.updateSharedExpense(groupId, expenseId, req, requireUserId(xUserId)));
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



    /* ── BALANCES ── */



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
