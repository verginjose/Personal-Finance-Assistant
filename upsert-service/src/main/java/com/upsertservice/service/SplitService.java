package com.upsertservice.service;

import com.upsertservice.dto.*;
import com.upsertservice.events.CacheEvictPublisher;
import com.upsertservice.model.*;
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
    private final CacheEvictPublisher cacheEvictPublisher;  // ADD THIS

    /* ─── GROUPS ─── */

    public ExpenseGroup createGroup(CreateGroupRequest req) {
        ExpenseGroup group = new ExpenseGroup();
        group.setName(req.getName());
        group.setDescription(req.getDescription());
        group.setCreatedBy(req.getCreatedBy());
        group.setCurrency(req.getCurrency() != null ? req.getCurrency() : "INR");
        group = groupRepo.save(group);
        // Auto-add creator as member
        addMemberInternal(group.getId(), req.getCreatedBy(), "Me");
        log.info("Group created: id={}, name={}", group.getId(), group.getName());
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

    public GroupMember addMember(Long groupId, AddMemberRequest req) {
        groupRepo.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group " + groupId + " not found"));
        if (memberRepo.existsByGroupIdAndUserId(groupId, req.getUserId())) {
            throw new IllegalArgumentException("User is already a member of this group");
        }
        return addMemberInternal(groupId, req.getUserId(), req.getName());
    }

    private GroupMember addMemberInternal(Long groupId, UUID userId, String name) {
        GroupMember m = new GroupMember();
        m.setGroupId(groupId);
        m.setUserId(userId);
        m.setName(name);
        return memberRepo.save(m);
    }

    @Transactional(readOnly = true)
    public List<GroupMember> getGroupMembers(Long groupId) {
        return memberRepo.findByGroupId(groupId);
    }

    /* ─── SHARED EXPENSES ─── */

    public SharedExpense addSharedExpense(CreateSharedExpenseRequest req) {
        ExpenseGroup group = groupRepo.findById(req.getGroupId())
                .orElseThrow(() -> new IllegalArgumentException("Group " + req.getGroupId() + " not found"));

        // Verify payer is a member
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
        log.info("Shared expense added: id={}, group={}, amount={}", expense.getId(), req.getGroupId(), req.getAmount());
        return expense;
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
            // payer's own split is auto-settled
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
        // fall back to equal if no explicit details
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

        // Build member name map
        Map<UUID, String> nameMap = members.stream()
                .collect(Collectors.toMap(GroupMember::getUserId, GroupMember::getName));

        // totalPaid[user] = sum of amounts they paid for
        Map<UUID, BigDecimal> totalPaid = new HashMap<>();
        for (SharedExpense e : expenses) {
            totalPaid.merge(e.getPaidBy(), e.getAmount(), BigDecimal::add);
        }

        // totalOwed[user] = sum of their unsettled splits
        Map<UUID, BigDecimal> totalOwed = new HashMap<>();
        for (ExpenseSplit s : allSplits) {
            if (!s.isSettled()) {
                totalOwed.merge(s.getUserId(), s.getAmount(), BigDecimal::add);
            }
        }

        // netBalance = totalPaid - totalOwed (positive = is owed, negative = owes)
        List<GroupBalanceResponse.MemberBalance> memberBalances = members.stream().map(m -> {
            UUID uid = m.getUserId();
            BigDecimal paid = totalPaid.getOrDefault(uid, BigDecimal.ZERO);
            BigDecimal owed = totalOwed.getOrDefault(uid, BigDecimal.ZERO);
            return new GroupBalanceResponse.MemberBalance(uid, m.getName(),
                    paid.subtract(owed).setScale(2, RoundingMode.HALF_UP),
                    paid.setScale(2, RoundingMode.HALF_UP),
                    owed.setScale(2, RoundingMode.HALF_UP));
        }).collect(Collectors.toList());

        // Debt minimization using greedy creditor-debtor simplification
        List<GroupBalanceResponse.SettlementSuggestion> suggestions =
                minimizeDebts(memberBalances, nameMap, group.getCurrency());

        return new GroupBalanceResponse(groupId, group.getName(), memberBalances, suggestions);
    }

    /** Greedy O(n²) debt simplification */
    private List<GroupBalanceResponse.SettlementSuggestion> minimizeDebts(
            List<GroupBalanceResponse.MemberBalance> balances,
            Map<UUID, String> nameMap,
            String currency) {

        // Debtors: negative net, Creditors: positive net
        TreeMap<BigDecimal, UUID> debtors   = new TreeMap<>();
        TreeMap<BigDecimal, UUID> creditors = new TreeMap<>();

        for (GroupBalanceResponse.MemberBalance mb : balances) {
            BigDecimal net = mb.getNetBalance();
            if (net.compareTo(BigDecimal.ZERO) < 0) {
                debtors.put(net, mb.getUserId());        // negative key = larger debt first
            } else if (net.compareTo(BigDecimal.ZERO) > 0) {
                creditors.put(net, mb.getUserId());
            }
        }

        // Turn into mutable maps
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

    public void settleDebt(Long groupId, UUID fromUserId, UUID toUserId) {
        List<SharedExpense> expenses = expenseRepo.findByGroupIdOrderByCreatedAtDesc(groupId);
        if (expenses.isEmpty()) return;
        List<Long> ids = expenses.stream().map(SharedExpense::getId).toList();
        List<ExpenseSplit> splits = splitRepo.findBySharedExpenseIdIn(ids)
                .stream()
                .filter(s -> s.getUserId().equals(fromUserId) && !s.isSettled())
                .filter(s -> {
                    // Only settle splits for expenses paid by toUserId
                    return expenses.stream()
                            .filter(e -> e.getId().equals(s.getSharedExpenseId()))
                            .anyMatch(e -> e.getPaidBy().equals(toUserId));
                })
                .collect(Collectors.toList());
        LocalDateTime now = LocalDateTime.now();
        splits.forEach(s -> { s.setSettled(true); s.setSettledAt(now); });
        splitRepo.saveAll(splits);
        log.info("Settled {} splits from {} to {}", splits.size(), fromUserId, toUserId);
    }
}
