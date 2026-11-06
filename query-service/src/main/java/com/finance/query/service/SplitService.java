package com.finance.query.service;

import com.finance.query.dto.*;
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
    private final com.finance.query.cache.CacheConsistencyGuard consistencyGuard;

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
                group.setCurrentUserRole(membership.isAdmin() ? "ADMIN" : "MEMBER");
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
                        group.setCurrentUserRole(m.isAdmin() ? "ADMIN" : "MEMBER");
                    });
                return group;
            });
    }


    /* ─── MEMBERS ─── */


    @Transactional(readOnly = true)
    public List<GroupMember> getGroupMembers(Long groupId) {
        // The member list is not cached and is read from the replica, so a
        // member added a moment ago can still be missing when the client
        // re-fetches immediately (replication lag, made longer by the
        // primary's synchronous_commit=off flush window). Wait for the group's
        // last write to become visible, exactly like a cached read would.
        com.finance.query.cache.CacheConsistencyGuard.Snapshot before =
                consistencyGuard.snapshot("group:" + groupId);
        if (before != null && !before.replicaCaughtUp()) {
            consistencyGuard.awaitReplica(before.wal(),
                    com.finance.query.cache.CacheConsistencyGuard.REPLICA_WAIT_MS);
        }
        return memberRepo.findByGroupId(groupId);
    }

    /**
     * Everyone the user shares an accepted group with, deduplicated, with the
     * group names that connect them. Used by the profile page so people can
     * open a fellow member's profile without leaving their own profile.
     */
    @Transactional(readOnly = true)
    public List<com.finance.query.dto.SharedPerson> getPeopleYouShareGroupsWith(UUID userId) {
        List<GroupMember> myMemberships = memberRepo.findByUserId(userId).stream()
                .filter(m -> m.getStatus() == GroupMember.InvitationStatus.ACCEPTED)
                .toList();
        if (myMemberships.isEmpty()) return List.of();

        Map<UUID, java.util.List<String>> groupsByPerson = new HashMap<>();
        Map<UUID, String> names = new HashMap<>();
        for (GroupMember mine : myMemberships) {
            String groupName = groupRepo.findById(mine.getGroupId())
                    .map(ExpenseGroup::getName)
                    .orElse("Group " + mine.getGroupId());
            for (GroupMember peer : memberRepo.findByGroupId(mine.getGroupId())) {
                if (peer.getStatus() != GroupMember.InvitationStatus.ACCEPTED) continue;
                if (peer.getUserId().equals(userId)) continue;
                groupsByPerson.computeIfAbsent(peer.getUserId(), k -> new java.util.ArrayList<>()).add(groupName);
                names.putIfAbsent(peer.getUserId(), peer.getName());
            }
        }
        return groupsByPerson.entrySet().stream()
                .map(e -> new com.finance.query.dto.SharedPerson(
                        e.getKey(), names.get(e.getKey()), e.getValue().size(), e.getValue()))
                .sorted(Comparator.comparing(com.finance.query.dto.SharedPerson::name,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    /* ─── SHARED EXPENSES ─── */

    /* ─── SHARED EXPENSES ─── */

    /* ─── SHARED EXPENSES ─── */

    /** Kept for callers that do not identify a user (no per-user fields). */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<SharedExpense> getGroupExpenses(Long groupId, int page, int size) {
        return getGroupExpenses(groupId, page, size, null);
    }

    /**
     * Expenses for a group, enriched with the requesting user's own share and
     * the payer's display name so the client never has to divide a total to
     * infer a personal outcome. Cached per user because those fields differ.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "group-expenses",
            key = "#groupId + '-' + #page + '-' + #size + '-' + (#userId != null ? #userId : 'anon')",
            sync = true)
    public org.springframework.data.domain.Page<SharedExpense> getGroupExpenses(Long groupId, int page, int size, UUID userId) {
        org.springframework.data.domain.Page<SharedExpense> result =
                expenseRepo.findByGroupIdOrderByCreatedAtDesc(groupId, org.springframework.data.domain.PageRequest.of(page, size));
        if (userId == null || !result.hasContent()) return result;

        List<SharedExpense> content = result.getContent();
        List<Long> expenseIds = content.stream().map(SharedExpense::getId).toList();
        List<ExpenseSplit> splits = splitRepo.findBySharedExpenseIdIn(expenseIds);
        Map<Long, BigDecimal> myShares = splits.stream()
                .filter(s -> userId.equals(s.getUserId()))
                .collect(Collectors.groupingBy(ExpenseSplit::getSharedExpenseId, LinkedHashMap::new,
                        Collectors.reducing(BigDecimal.ZERO, ExpenseSplit::getAmount, BigDecimal::add)));

        Map<UUID, String> names = new HashMap<>();
        for (GroupMember member : memberRepo.findByGroupId(groupId)) {
            names.put(member.getUserId(), member.getName());
        }

        Map<Long, SharedExpense> byId = new HashMap<>();
        for (SharedExpense expense : content) byId.put(expense.getId(), expense);

        // A settlement's receiver is the person in the split who did not pay.
        Map<Long, String> settlementReceivers = new HashMap<>();
        for (ExpenseSplit split : splits) {
            SharedExpense expense = byId.get(split.getSharedExpenseId());
            if (expense != null
                    && expense.getSplitType() == SharedExpense.SplitType.SETTLEMENT
                    && !split.getUserId().equals(expense.getPaidBy())) {
                String receiver = split.getUserName() != null ? split.getUserName() : names.get(split.getUserId());
                settlementReceivers.put(expense.getId(), receiver);
            }
        }

        for (SharedExpense expense : content) {
            expense.setMyShare(myShares.get(expense.getId()));
            expense.setPaidByName(names.get(expense.getPaidBy()));
            expense.setSettlementToName(settlementReceivers.get(expense.getId()));
        }
        return result;
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
        Map<UUID, BigDecimal> totalSpent = new HashMap<>();
        for (SharedExpense e : expenses) {
            totalPaid.merge(e.getPaidBy(), e.getAmount(), BigDecimal::add);
            // Settlement transfers are kept in totalPaid for the net balance but
            // are not spending, and must not inflate "Spending by member".
            if (e.getSplitType() != SharedExpense.SplitType.SETTLEMENT) {
                totalSpent.merge(e.getPaidBy(), e.getAmount(), BigDecimal::add);
            }
        }

        Map<UUID, BigDecimal> totalOwed = new HashMap<>();
        for (ExpenseSplit s : allSplits) {
            totalOwed.merge(s.getUserId(), s.getAmount(), BigDecimal::add);
        }

        List<GroupBalanceResponse.MemberBalance> memberBalances = members.stream().map(m -> {
            UUID uid = m.getUserId();
            BigDecimal paid = totalPaid.getOrDefault(uid, BigDecimal.ZERO);
            BigDecimal spent = totalSpent.getOrDefault(uid, BigDecimal.ZERO);
            BigDecimal owed = totalOwed.getOrDefault(uid, BigDecimal.ZERO);
            return new GroupBalanceResponse.MemberBalance(uid, m.getName(),
                    paid.subtract(owed).setScale(2, RoundingMode.HALF_UP),
                    paid.setScale(2, RoundingMode.HALF_UP),
                    spent.setScale(2, RoundingMode.HALF_UP),
                    owed.setScale(2, RoundingMode.HALF_UP));
        }).collect(Collectors.toList());

        List<GroupBalanceResponse.SettlementSuggestion> suggestions = minimizeDebts(memberBalances, nameMap,
                group.getCurrency());

        // Settlement payments move money between members; they are not spend.
        BigDecimal totalSpend = expenses.stream()
                .filter(e -> e.getSplitType() != SharedExpense.SplitType.SETTLEMENT)
                .map(SharedExpense::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        return new GroupBalanceResponse(groupId, group.getName(), memberBalances, suggestions, totalSpend);
    }

    // SYNC-WITH-COMMAND-SERVICE: minimizeDebts and computeNetBalancesForUser are duplicated
    // in command-service/SplitService.java. Any algorithmic change MUST be applied to both.
    // Future improvement: extract to a shared Maven module (finance-common).
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
