package com.finance.query.service;

import com.finance.query.dto.*;
import com.finance.query.events.CacheEvictPublisher;
import com.finance.query.model.*;
import com.finance.query.model.GroupActivity.ActivityType;
import com.finance.query.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.security.auth.callback.LanguageCallback;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final org.springframework.cache.CacheManager cacheManager;
    private final GroupActivityRepository groupActivityRepo;

    @Lazy
    @Autowired
    private SplitService self;

    /* ─── GROUPS ─── */


    @Transactional(readOnly = true)
    @Cacheable(value = "user-groups", key = "#userId", sync = true)
    public List<ExpenseGroup> getUserGroups(UUID userId) {
        List<ExpenseGroup> groups = groupRepo.findGroupsByMember(userId);
        if (groups.isEmpty()) return groups;

        List<Long> groupIds = groups.stream().map(ExpenseGroup::getId).toList();

        Map<Long, GroupMember> membershipByGroup = memberRepo.findByGroupIdInAndUserId(groupIds, userId)
                .stream().collect(Collectors.toMap(GroupMember::getGroupId, m -> m));

        List<SharedExpense> allExpenses = expenseRepo.findByGroupIdIn(groupIds);
        List<Long> expenseIds = allExpenses.stream().map(SharedExpense::getId).toList();
        List<ExpenseSplit> allSplits = expenseIds.isEmpty() ? List.of()
                : splitRepo.findBySharedExpenseIdIn(expenseIds);

        Map<Long, BigDecimal> netBalances = computeNetBalancesForUser(userId, groupIds, allExpenses, allSplits);

        for (ExpenseGroup group : groups) {
            GroupMember membership = membershipByGroup.get(group.getId());
            if (membership != null) {
                group.setIsArchived(membership.isArchived());
                group.setCurrentUserStatus(membership.getStatus().name());
            }
            group.setCurrentUserNetBalance(netBalances.getOrDefault(group.getId(), BigDecimal.ZERO));
        }

        return groups;
    }


    private Map<Long, BigDecimal> computeNetBalancesForUser(UUID userId, List<Long> groupIds,
                                                            List<SharedExpense> allExpenses, List<ExpenseSplit> allSplits) {

        // map expenseId -> groupId
        Map<Long, Long> expenseToGroup = allExpenses.stream()
                .collect(Collectors.toMap(SharedExpense::getId, SharedExpense::getGroupId));

        Map<Long, BigDecimal> paidByGroup = new HashMap<>();
        for (SharedExpense e : allExpenses) {
            if (e.getPaidBy().equals(userId)) {
                paidByGroup.merge(e.getGroupId(), e.getAmount(), BigDecimal::add);
            }
        }

        Map<Long, BigDecimal> owedByGroup = new HashMap<>();
        for (ExpenseSplit s : allSplits) {
            if (s.getUserId().equals(userId)) {
                Long gid = expenseToGroup.get(s.getSharedExpenseId());
                if (gid != null) {
                    owedByGroup.merge(gid, s.getAmount(), BigDecimal::add);
                }
            }
        }

        Map<Long, BigDecimal> result = new HashMap<>();
        for (Long gid : groupIds) {
            BigDecimal paid = paidByGroup.getOrDefault(gid, BigDecimal.ZERO);
            BigDecimal owed = owedByGroup.getOrDefault(gid, BigDecimal.ZERO);
            result.put(gid, paid.subtract(owed).setScale(2, RoundingMode.HALF_UP));
        }
        return result;
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "group-details", key = "#groupId + '-' + #userId", sync = true)
    public Optional<ExpenseGroup> getGroup(Long groupId, UUID userId) {
        return groupRepo.findById(groupId)
            .filter(g -> !g.isDeleted())
            .map(group -> {
                memberRepo.findByGroupId(group.getId()).stream()
                    .filter(m -> m.getUserId().equals(userId))
                    .findFirst()
                    .ifPresent(m -> {
                        group.setIsArchived(m.isArchived());
                        group.setCurrentUserStatus(m.getStatus().name());
                    });
                return group;
            });
    }


    /* ─── MEMBERS ─── */


    @Transactional(readOnly = true)
    public List<GroupMember> getGroupMembers(Long groupId) {
        return memberRepo.findByGroupId(groupId);
    }

    /* ─── SHARED EXPENSES ─── */

    /* ─── SHARED EXPENSES ─── */

    @Transactional(readOnly = true)
    @Cacheable(value = "group-expenses", key = "#groupId + '-' + #page + '-' + #size", sync = true)
    public org.springframework.data.domain.Page<SharedExpense> getGroupExpenses(Long groupId, int page, int size) {
        return expenseRepo.findByGroupIdOrderByCreatedAtDesc(groupId, org.springframework.data.domain.PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<GroupActivity> getGroupActivity(Long groupId, int page, int size) {
        groupRepo.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group " + groupId + " not found"));
        return groupActivityRepo.findByGroupIdOrderByCreatedAtDesc(groupId, org.springframework.data.domain.PageRequest.of(page, size));
    }

    /* ─── BALANCES (debt minimization) ─── */

    @Transactional(readOnly = true)
    @Cacheable(value = "group-balances", key = "#groupId", sync = true)
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
            totalOwed.merge(s.getUserId(), s.getAmount(), BigDecimal::add);
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

        List<GroupBalanceResponse.SettlementSuggestion> suggestions = minimizeDebts(memberBalances, nameMap,
                group.getCurrency());

        return new GroupBalanceResponse(groupId, group.getName(), memberBalances, suggestions);
    }

    private List<GroupBalanceResponse.SettlementSuggestion> minimizeDebts(
            List<GroupBalanceResponse.MemberBalance> balances,
            Map<UUID, String> nameMap,
            String currency) {

        Map<UUID, BigDecimal> debt = new HashMap<>();
        Map<UUID, BigDecimal> credit = new HashMap<>();

        for (GroupBalanceResponse.MemberBalance mb : balances) {
            BigDecimal net = mb.getNetBalance();
            if (net.compareTo(BigDecimal.ZERO) < 0)
                debt.put(mb.getUserId(), net.negate());
            else if (net.compareTo(BigDecimal.ZERO) > 0)
                credit.put(mb.getUserId(), net);
        }

        if (debt.isEmpty() || credit.isEmpty()) {
            return List.of();
        }
        // Sort by amount descending — largest debts/credits settled first
        List<UUID> debtorList = debt.keySet().stream()
                .sorted(Comparator.comparing(debt::get).reversed())
                .collect(Collectors.toCollection(ArrayList::new));
        List<UUID> creditorList = credit.keySet().stream()
                .sorted(Comparator.comparing(credit::get).reversed())
                .collect(Collectors.toCollection(ArrayList::new));

        List<GroupBalanceResponse.SettlementSuggestion> suggestions = new ArrayList<>();
        int di = 0, ci = 0;

        while (di < debtorList.size() && ci < creditorList.size()) {
            UUID debtor = debtorList.get(di);
            UUID creditor = creditorList.get(ci);

            BigDecimal debtAmt = debt.getOrDefault(debtor, BigDecimal.ZERO);
            BigDecimal creditAmt = credit.getOrDefault(creditor, BigDecimal.ZERO);

            if (debtAmt.compareTo(BigDecimal.ZERO) <= 0) {
                di++;
                continue;
            }
            if (creditAmt.compareTo(BigDecimal.ZERO) <= 0) {
                ci++;
                continue;
            }

            BigDecimal settle = debtAmt.min(creditAmt).setScale(2, RoundingMode.HALF_UP);
            suggestions.add(new GroupBalanceResponse.SettlementSuggestion(
                    debtor, nameMap.getOrDefault(debtor, debtor.toString()),
                    creditor, nameMap.getOrDefault(creditor, creditor.toString()),
                    settle, currency));

            debt.put(debtor, debtAmt.subtract(settle));
            credit.put(creditor, creditAmt.subtract(settle));

            if (debt.get(debtor).compareTo(BigDecimal.ZERO) == 0)
                di++;
            if (credit.get(creditor).compareTo(BigDecimal.ZERO) == 0)
                ci++;
        }

        return suggestions;
    }


}
