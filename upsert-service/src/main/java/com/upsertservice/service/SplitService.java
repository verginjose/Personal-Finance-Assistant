package com.upsertservice.service;

import com.upsertservice.dto.*;
import com.upsertservice.events.CacheEvictPublisher;
import com.upsertservice.model.*;
import com.upsertservice.model.GroupActivity.ActivityType;
import com.upsertservice.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class SplitService {

    private final ExpenseGroupRepository groupRepo;
    private final GroupMemberRepository memberRepo;
    private final SharedExpenseRepository expenseRepo;
    private final ExpenseSplitRepository splitRepo;
    private final ExpenseTransactionLinkRepository transactionLinkRepo;
    private final TransactionEntryService transactionEntryService;
    private final UserValidationService userValidationService;
    private final GroupActivityService groupActivityService;
    private final CacheEvictPublisher cacheEvictPublisher;
    private final NotificationService notificationService;

    /* ─── GROUPS ─── */

    public ExpenseGroup createGroup(CreateGroupRequest req) {
        ExpenseGroup group = new ExpenseGroup();
        group.setName(req.getName());
        group.setDescription(req.getDescription());
        group.setCreatedBy(req.getCreatedBy());
        group.setCurrency(req.getCurrency() != null ? req.getCurrency() : "INR");
        group = groupRepo.save(group);
        RegisteredUser creator = userValidationService.requireRegisteredUser(req.getCreatedBy());
        String creatorName = userValidationService.displayName(creator);
        GroupMember creatorMember = addMemberInternal(group.getId(), req.getCreatedBy(), creatorName);
        creatorMember.setStatus(GroupMember.InvitationStatus.ACCEPTED);
        memberRepo.save(creatorMember);
        groupActivityService.log(group.getId(), req.getCreatedBy(), creatorName,
                ActivityType.GROUP_CREATED,
                "@" + creatorName + " created the group \"" + group.getName() + "\"",
                group.getId());
        log.info("Group created: id={}, name={}", group.getId(), group.getName());
        return group;
    }

    public ExpenseGroup updateGroup(Long groupId, UpdateGroupRequest req, UUID actorUserId) {
        ExpenseGroup group = groupRepo.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group " + groupId + " not found"));
        requireGroupMember(groupId, actorUserId);
        
        group.setName(req.getName());
        group.setDescription(req.getDescription());
        if (req.getCurrency() != null) {
            group.setCurrency(req.getCurrency());
        }
        group = groupRepo.save(group);
        
        String actorName = resolveMemberName(groupId, actorUserId);
        groupActivityService.log(groupId, actorUserId, actorName,
                ActivityType.GROUP_CREATED, // Reuse or create new ActivityType, currently reuse
                "@" + actorName + " updated group details",
                groupId);
        return group;
    }

    @Transactional(readOnly = true)
    public List<ExpenseGroup> getUserGroups(UUID userId) {
        return groupRepo.findGroupsByMember(userId);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<ExpenseGroup> getGroup(Long groupId) {
        return groupRepo.findById(groupId);
    }

    /* ─── MEMBERS ─── */

    public GroupMember addMember(Long groupId, AddMemberRequest req, UUID actorUserId) {
        groupRepo.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group " + groupId + " not found"));
        requireGroupMember(groupId, actorUserId);
        RegisteredUser user = userValidationService.requireRegisteredUser(req.getUserId());
        if (memberRepo.existsByGroupIdAndUserId(groupId, req.getUserId())) {
            throw new IllegalArgumentException("User is already a member of this group");
        }
        String name = req.getName() != null && !req.getName().isBlank()
                ? req.getName()
                : userValidationService.displayName(user);
        GroupMember member = addMemberInternal(groupId, req.getUserId(), name);
        String actorName = resolveMemberName(groupId, actorUserId);
        groupActivityService.log(groupId, actorUserId, actorName,
                ActivityType.MEMBER_ADDED,
                "@" + actorName + " added @" + name + " to the group",
                member.getId());
                
        // Push notification
        ExpenseGroup group = groupRepo.findById(groupId).orElse(null);
        if (group != null) {
            notificationService.sendNotification(req.getUserId(), Map.of(
                "status", "INFO",
                "message", "You've been invited to join the group: " + group.getName(),
                "event", "group-invite",
                "groupId", groupId
            ));
        }
        
        return member;
    }

    private GroupMember addMemberInternal(Long groupId, UUID userId, String name) {
        GroupMember m = new GroupMember();
        m.setGroupId(groupId);
        m.setUserId(userId);
        m.setName(name);
        m.setStatus(GroupMember.InvitationStatus.PENDING);
        return memberRepo.save(m);
    }

    public void acceptInvitation(Long groupId, UUID userId) {
        GroupMember member = memberRepo.findByGroupId(groupId).stream()
                .filter(m -> m.getUserId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Membership not found"));
        
        if (member.getStatus() == GroupMember.InvitationStatus.ACCEPTED) {
            return;
        }
        
        member.setStatus(GroupMember.InvitationStatus.ACCEPTED);
        memberRepo.save(member);
        
        String actorName = resolveMemberName(groupId, userId);
        groupActivityService.log(groupId, userId, actorName,
                ActivityType.MEMBER_ADDED,
                "@" + actorName + " joined the group",
                member.getId());
    }

    public void rejectInvitation(Long groupId, UUID userId) {
        GroupMember member = memberRepo.findByGroupId(groupId).stream()
                .filter(m -> m.getUserId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Membership not found"));
        
        if (member.getStatus() == GroupMember.InvitationStatus.ACCEPTED) {
            throw new IllegalArgumentException("Cannot reject an already accepted invitation. Use leave group instead.");
        }
        
        memberRepo.delete(member);
    }

    public void leaveGroup(Long groupId, UUID userId) {
        GroupMember member = memberRepo.findByGroupId(groupId).stream()
                .filter(m -> m.getUserId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Membership not found"));
                
        // Ensure balance is 0
        GroupBalanceResponse balances = getGroupBalances(groupId);
        boolean hasBalance = balances.getMemberBalances().stream()
                .filter(b -> b.getUserId().equals(userId))
                .anyMatch(b -> b.getNetBalance().compareTo(BigDecimal.ZERO) != 0);
                
        if (hasBalance) {
            throw new IllegalArgumentException("Cannot leave the group with a non-zero balance. Please settle your debts first.");
        }
        
        memberRepo.delete(member);
        
        String actorName = member.getName();
        groupActivityService.log(groupId, userId, actorName,
                ActivityType.MEMBER_ADDED, // Reuse ActivityType, maybe we should add a LEAVE type, but reusing is fine for now
                "@" + actorName + " left the group",
                member.getId());
    }

    @Transactional(readOnly = true)
    public List<GroupMember> getGroupMembers(Long groupId) {
        return memberRepo.findByGroupId(groupId);
    }

    /* ─── SHARED EXPENSES ─── */

    public SharedExpense addSharedExpense(CreateSharedExpenseRequest req, UUID actorUserId) {
        ExpenseGroup group = groupRepo.findById(req.getGroupId())
                .orElseThrow(() -> new IllegalArgumentException("Group " + req.getGroupId() + " not found"));
        requireGroupMember(req.getGroupId(), actorUserId);

        if (!memberRepo.existsByGroupIdAndUserId(req.getGroupId(), req.getPaidBy())) {
            throw new SecurityException("Payer is not a member of this group");
        }

        SharedExpense expense = new SharedExpense();
        expense.setGroupId(req.getGroupId());
        expense.setDescription(req.getDescription());
        expense.setAmount(req.getAmount());
        expense.setCurrency(req.getCurrency() != null ? req.getCurrency() : group.getCurrency());
        expense.setPaidBy(req.getPaidBy());
        expense.setSplitType(req.getSplitType() != null
                ? SharedExpense.SplitType.valueOf(req.getSplitType().toUpperCase())
                : SharedExpense.SplitType.EQUAL);
        if (req.getExpenseCategory() != null && !req.getExpenseCategory().isBlank()) {
            try {
                expense.setExpenseCategory(SharedExpense.ExpenseCategory.valueOf(req.getExpenseCategory().toUpperCase()));
            } catch (IllegalArgumentException ignored) {}
        }
        expense = expenseRepo.save(expense);

        generateSplits(expense, req);
        List<ExpenseSplit> splits = splitRepo.findBySharedExpenseId(expense.getId());
        createPersonalTransactions(expense, splits, group);
        evictAnalyticsCacheForSplits(splits, expense.getId(), "SPLIT_EXPENSE");

        String actorName = resolveMemberName(req.getGroupId(), actorUserId);
        groupActivityService.log(req.getGroupId(), actorUserId, actorName,
                ActivityType.EXPENSE_ADDED,
                "@" + actorName + " added expense \"" + expense.getDescription()
                        + "\" (" + expense.getAmount() + " " + expense.getCurrency() + ")",
                expense.getId());
        log.info("Shared expense added: id={}, group={}, amount={}", expense.getId(), req.getGroupId(), req.getAmount());
        
        final String desc = expense.getDescription();
        final Long gid = req.getGroupId();
        
        // Push notification to involved members
        splits.forEach(s -> {
            if (!s.getUserId().equals(actorUserId) && s.getAmount().compareTo(BigDecimal.ZERO) > 0) {
                notificationService.sendNotification(s.getUserId(), Map.of(
                    "status", "INFO",
                    "message", actorName + " added a new expense: " + desc,
                    "event", "expense-added",
                    "groupId", gid
                ));
            }
        });
        
        return expense;
    }

    public void deleteSharedExpense(Long groupId, Long expenseId, UUID actorUserId) {
        ExpenseGroup group = groupRepo.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group " + groupId + " not found"));
        requireGroupMember(groupId, actorUserId);

        SharedExpense expense = expenseRepo.findById(expenseId)
                .orElseThrow(() -> new IllegalArgumentException("Expense " + expenseId + " not found"));
        if (!expense.getGroupId().equals(groupId)) {
            throw new IllegalArgumentException("Expense does not belong to this group");
        }

        List<ExpenseSplit> splits = splitRepo.findBySharedExpenseId(expenseId);
        boolean hasRecordedSettlements = splits.stream()
                .anyMatch(s -> s.isSettled() && !s.getUserId().equals(expense.getPaidBy()));
        if (hasRecordedSettlements) {
            throw new IllegalStateException("Cannot delete an expense with recorded settlements");
        }

        List<ExpenseTransactionLink> links = transactionLinkRepo.findBySharedExpenseId(expenseId);
        for (ExpenseTransactionLink link : links) {
            transactionEntryService.deleteEntry(link.getTransactionEntryId(), link.getUserId());
        }
        transactionLinkRepo.deleteBySharedExpenseId(expenseId);
        splitRepo.deleteAll(splits);
        expenseRepo.delete(expense);

        evictAnalyticsCacheForSplits(splits, expenseId, "SPLIT_EXPENSE_DELETE");

        String actorName = resolveMemberName(groupId, actorUserId);
        groupActivityService.log(groupId, actorUserId, actorName,
                ActivityType.EXPENSE_DELETED,
                "@" + actorName + " deleted expense \"" + expense.getDescription()
                        + "\" (" + expense.getAmount() + " " + expense.getCurrency() + ")",
                expenseId);
        log.info("Shared expense deleted: id={}, group={}", expenseId, groupId);
    }

    private void createPersonalTransactions(SharedExpense expense, List<ExpenseSplit> splits, ExpenseGroup group) {
        Category category = toTransactionCategory(expense);
        String groupLabel = group.getName() != null ? group.getName() : "group";
        for (ExpenseSplit split : splits) {
            if (split.getAmount().compareTo(BigDecimal.ZERO) <= 0) continue;
            CreateEntryRequest entry = new CreateEntryRequest();
            entry.setUserId(split.getUserId());
            entry.setName(expense.getDescription());
            entry.setAmount(split.getAmount());
            entry.setType(TransactionType.EXPENSE);
            entry.setCurrency(expense.getCurrency());
            entry.setCategory(category);
            entry.setDescription("Split expense · " + groupLabel);
            CreateEntryResponse saved = transactionEntryService.createEntry(entry, null, false);
            transactionLinkRepo.save(new ExpenseTransactionLink(null, expense.getId(),
                    split.getUserId(), saved.getId()));
        }
    }

    private void evictAnalyticsCacheForSplits(List<ExpenseSplit> splits, Long referenceId, String operation) {
        Set<UUID> affectedUsers = splits.stream()
                .filter(s -> s.getAmount().compareTo(BigDecimal.ZERO) > 0)
                .map(ExpenseSplit::getUserId)
                .collect(Collectors.toSet());
        cacheEvictPublisher.publishForUsers(affectedUsers, operation, referenceId);
    }

    private Category toTransactionCategory(SharedExpense expense) {
        if (expense.getExpenseCategory() == null) {
            return Category.OTHERS;
        }
        return Category.valueOf(expense.getExpenseCategory().name());
    }
    private void generateSplits(SharedExpense expense, CreateSharedExpenseRequest req) {
        List<GroupMember> members = memberRepo.findByGroupId(req.getGroupId());
        if (members.isEmpty()) return;

        switch (expense.getSplitType()) {
            case EQUAL -> generateEqualSplits(expense, members);
            case PERCENTAGE -> generatePercentageSplits(expense, req, members);
            case EXACT -> generateExactSplits(expense, req, members);
        }
    }

    private void generateEqualSplits(SharedExpense expense, List<GroupMember> members) {
        BigDecimal each = expense.getAmount()
                .divide(BigDecimal.valueOf(members.size()), 2, RoundingMode.HALF_UP);
        List<ExpenseSplit> splits = members.stream().map(m -> {
            ExpenseSplit s = new ExpenseSplit();
            s.setSharedExpenseId(expense.getId());
            s.setUserId(m.getUserId());
            s.setUserName(m.getName());
            s.setAmount(each);
            s.setSettled(m.getUserId().equals(expense.getPaidBy()));
            return s;
        }).collect(Collectors.toList());
        splitRepo.saveAll(splits);
    }

    private void generatePercentageSplits(SharedExpense expense,
                                           CreateSharedExpenseRequest req,
                                           List<GroupMember> members) {
        Map<UUID, BigDecimal> pctMap = req.getSplitDetails() == null ? Map.of()
                : req.getSplitDetails().stream()
                  .collect(Collectors.toMap(d -> d.getUserId(), d -> d.getValue()));
        if (pctMap.isEmpty()) { generateEqualSplits(expense, members); return; }

        List<ExpenseSplit> splits = members.stream().map(m -> {
            BigDecimal pct = pctMap.getOrDefault(m.getUserId(), BigDecimal.ZERO);
            BigDecimal share = expense.getAmount().multiply(pct)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            ExpenseSplit s = new ExpenseSplit();
            s.setSharedExpenseId(expense.getId());
            s.setUserId(m.getUserId());
            s.setUserName(m.getName());
            s.setAmount(share);
            s.setSettled(m.getUserId().equals(expense.getPaidBy()));
            return s;
        }).collect(Collectors.toList());
        splitRepo.saveAll(splits);
    }

    private void generateExactSplits(SharedExpense expense,
                                      CreateSharedExpenseRequest req,
                                      List<GroupMember> members) {
        if (req.getSplitDetails() == null || req.getSplitDetails().isEmpty()) {
            generateEqualSplits(expense, members);
            return;
        }
        Map<UUID, CreateSharedExpenseRequest.SplitDetailRequest> detailMap =
                req.getSplitDetails().stream()
                   .collect(Collectors.toMap(d -> d.getUserId(), d -> d));
        List<ExpenseSplit> splits = members.stream().map(m -> {
            CreateSharedExpenseRequest.SplitDetailRequest detail = detailMap.get(m.getUserId());
            BigDecimal share = detail != null ? detail.getValue() : BigDecimal.ZERO;
            ExpenseSplit s = new ExpenseSplit();
            s.setSharedExpenseId(expense.getId());
            s.setUserId(m.getUserId());
            s.setUserName(m.getName());
            s.setAmount(share);
            s.setSettled(m.getUserId().equals(expense.getPaidBy()));
            return s;
        }).collect(Collectors.toList());
        splitRepo.saveAll(splits);
    }

    @Transactional(readOnly = true)
    public List<SharedExpense> getGroupExpenses(Long groupId) {
        return expenseRepo.findByGroupIdOrderByCreatedAtDesc(groupId);
    }

    @Transactional(readOnly = true)
    public List<GroupActivity> getGroupActivity(Long groupId) {
        groupRepo.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group " + groupId + " not found"));
        return groupActivityService.getGroupActivity(groupId);
    }

    /* ─── BALANCES (debt minimization) ─── */

    @Transactional(readOnly = true)
    public GroupBalanceResponse getGroupBalances(Long groupId) {
        ExpenseGroup group = groupRepo.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group " + groupId + " not found"));
        List<GroupMember> members = memberRepo.findByGroupId(groupId);
        List<SharedExpense> expenses = expenseRepo.findByGroupIdOrderByCreatedAtDesc(groupId);
        List<Long> expenseIds = expenses.stream().map(SharedExpense::getId).toList();
        List<ExpenseSplit> allSplits = expenseIds.isEmpty() ? List.of()
                : splitRepo.findBySharedExpenseIdIn(expenseIds);

        Map<UUID, String> nameMap = members.stream()
                .collect(Collectors.toMap(GroupMember::getUserId, GroupMember::getName));

        Map<UUID, BigDecimal> totalPaid = new HashMap<>();
        for (SharedExpense e : expenses) {
            totalPaid.merge(e.getPaidBy(), e.getAmount(), BigDecimal::add);
        }

        Map<UUID, BigDecimal> totalOwed = new HashMap<>();
        for (ExpenseSplit s : allSplits) {
            if (!s.isSettled()) {
                totalOwed.merge(s.getUserId(), s.getAmount(), BigDecimal::add);
            }
        }

        List<GroupBalanceResponse.MemberBalance> memberBalances = members.stream().map(m -> {
            UUID uid = m.getUserId();
            BigDecimal paid = totalPaid.getOrDefault(uid, BigDecimal.ZERO);
            BigDecimal owed = totalOwed.getOrDefault(uid, BigDecimal.ZERO);
            return new GroupBalanceResponse.MemberBalance(uid, m.getName(),
                    paid.subtract(owed).setScale(2, RoundingMode.HALF_UP),
                    paid.setScale(2, RoundingMode.HALF_UP),
                    owed.setScale(2, RoundingMode.HALF_UP));
        }).collect(Collectors.toList());

        List<GroupBalanceResponse.SettlementSuggestion> suggestions =
                minimizeDebts(memberBalances, nameMap, group.getCurrency());

        return new GroupBalanceResponse(groupId, group.getName(), memberBalances, suggestions);
    }

    private List<GroupBalanceResponse.SettlementSuggestion> minimizeDebts(
            List<GroupBalanceResponse.MemberBalance> balances,
            Map<UUID, String> nameMap,
            String currency) {

        TreeMap<BigDecimal, UUID> debtors   = new TreeMap<>();
        TreeMap<BigDecimal, UUID> creditors = new TreeMap<>();

        for (GroupBalanceResponse.MemberBalance mb : balances) {
            BigDecimal net = mb.getNetBalance();
            if (net.compareTo(BigDecimal.ZERO) < 0) {
                debtors.put(net, mb.getUserId());
            } else if (net.compareTo(BigDecimal.ZERO) > 0) {
                creditors.put(net, mb.getUserId());
            }
        }

        Map<UUID, BigDecimal> debt    = new HashMap<>();
        Map<UUID, BigDecimal> credit  = new HashMap<>();
        debtors.forEach((k, v)   -> debt.put(v, k.negate()));
        creditors.forEach((k, v) -> credit.put(v, k));

        List<GroupBalanceResponse.SettlementSuggestion> suggestions = new ArrayList<>();
        List<UUID> debtorList   = new ArrayList<>(debt.keySet());
        List<UUID> creditorList = new ArrayList<>(credit.keySet());

        int di = 0, ci = 0;
        while (di < debtorList.size() && ci < creditorList.size()) {
            UUID debtor   = debtorList.get(di);
            UUID creditor = creditorList.get(ci);
            BigDecimal debtAmt   = debt.getOrDefault(debtor, BigDecimal.ZERO);
            BigDecimal creditAmt = credit.getOrDefault(creditor, BigDecimal.ZERO);

            if (debtAmt.compareTo(BigDecimal.ZERO) <= 0)  { di++; continue; }
            if (creditAmt.compareTo(BigDecimal.ZERO) <= 0) { ci++; continue; }

            BigDecimal settle = debtAmt.min(creditAmt).setScale(2, RoundingMode.HALF_UP);
            suggestions.add(new GroupBalanceResponse.SettlementSuggestion(
                    debtor, nameMap.getOrDefault(debtor, debtor.toString()),
                    creditor, nameMap.getOrDefault(creditor, creditor.toString()),
                    settle, currency));

            debt.put(debtor, debtAmt.subtract(settle));
            credit.put(creditor, creditAmt.subtract(settle));

            if (debt.get(debtor).compareTo(BigDecimal.ZERO) == 0)   di++;
            if (credit.get(creditor).compareTo(BigDecimal.ZERO) == 0) ci++;
        }
        return suggestions;
    }

    /* ─── SETTLEMENT ─── */

    public void settleDebt(Long groupId, UUID fromUserId, UUID toUserId, UUID actorUserId) {
        requireGroupMember(groupId, actorUserId);
        List<SharedExpense> expenses = expenseRepo.findByGroupIdOrderByCreatedAtDesc(groupId);
        if (expenses.isEmpty()) return;
        List<Long> ids = expenses.stream().map(SharedExpense::getId).toList();
        List<ExpenseSplit> splits = splitRepo.findBySharedExpenseIdIn(ids)
                .stream()
                .filter(s -> s.getUserId().equals(fromUserId) && !s.isSettled())
                .filter(s -> expenses.stream()
                        .filter(e -> e.getId().equals(s.getSharedExpenseId()))
                        .anyMatch(e -> e.getPaidBy().equals(toUserId)))
                .collect(Collectors.toList());
        if (splits.isEmpty()) return;

        BigDecimal settledTotal = splits.stream()
                .map(ExpenseSplit::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        LocalDateTime now = LocalDateTime.now();
        splits.forEach(s -> { s.setSettled(true); s.setSettledAt(now); });
        splitRepo.saveAll(splits);
        cacheEvictPublisher.publishForUsers(Set.of(fromUserId, toUserId), "SPLIT_SETTLE", groupId);

        String fromName = resolveMemberName(groupId, fromUserId);
        String toName = resolveMemberName(groupId, toUserId);
        String actorName = resolveMemberName(groupId, actorUserId);
        groupActivityService.log(groupId, actorUserId, actorName,
                ActivityType.SETTLEMENT_RECORDED,
                "@" + fromName + " recorded payment to @" + toName
                        + " (" + settledTotal.setScale(2, RoundingMode.HALF_UP) + ")",
                groupId);
        log.info("Settled {} splits from {} to {}", splits.size(), fromUserId, toUserId);
        
        // Push notification
        UUID targetNotificationUser = fromUserId.equals(actorUserId) ? toUserId : fromUserId;
        notificationService.sendNotification(targetNotificationUser, Map.of(
            "status", "SUCCESS",
            "message", actorName + " recorded a ₹" + settledTotal.setScale(2, RoundingMode.HALF_UP) + " settlement with you.",
            "event", "debt-settled",
            "groupId", groupId
        ));
    }

    private void requireGroupMember(Long groupId, UUID userId) {
        if (!memberRepo.existsByGroupIdAndUserId(groupId, userId)) {
            throw new SecurityException("User is not a member of this group");
        }
    }

    private String resolveMemberName(Long groupId, UUID userId) {
        return memberRepo.findByGroupId(groupId).stream()
                .filter(m -> m.getUserId().equals(userId))
                .map(GroupMember::getName)
                .findFirst()
                .orElseGet(() -> userValidationService.displayName(
                        userValidationService.requireRegisteredUser(userId)));
    }
}
