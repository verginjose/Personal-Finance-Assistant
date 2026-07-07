package com.finance.command.service;

import com.finance.command.dto.*;
import com.finance.command.events.CacheEvictPublisher;
import com.finance.command.model.*;
import com.finance.command.model.GroupActivity.ActivityType;
import com.finance.command.repository.*;
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
    private final ExpenseTransactionLinkRepository transactionLinkRepo;
    private final TransactionEntryRepository transactionEntryRepo;
    private final TransactionEntryService transactionEntryService;
    private final UserValidationService userValidationService;
    private final GroupActivityService groupActivityService;
    private final CacheEvictPublisher cacheEvictPublisher;
    private final NotificationService notificationService;
    private final org.springframework.cache.CacheManager cacheManager;
    private final com.finance.command.cache.QueryCacheEvictor queryCacheEvictor;

    @Lazy
    @Autowired
    private SplitService self;

    /* ─── GROUPS ─── */

    @CacheEvict(value = "user-groups", key = "#req.createdBy")
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
        cacheEvictPublisher.publishForUsers(Set.of(req.getCreatedBy()), "GROUP_CREATED", group.getId());
        evictUserGroupsForMembers(group.getId());
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

        // Evict own caches (finance:upsert:v1:*)
        var members = memberRepo.findByGroupId(groupId);
        var detailsCache = cacheManager.getCache("group-details");
        if (detailsCache != null) {
            members.forEach(m -> detailsCache.evict(groupId + "-" + m.getUserId()));
        }
        var userGroupsCache = cacheManager.getCache("user-groups");
        if (userGroupsCache != null) {
            members.forEach(m -> userGroupsCache.evict(m.getUserId()));
        }

        // Directly evict query-service keys on the shared Redis instance — no pub/sub needed
        Set<UUID> memberIds = members.stream().map(GroupMember::getUserId).collect(Collectors.toSet());
        queryCacheEvictor.evictGroupKeys(groupId, memberIds);

        String actorName = resolveMemberName(groupId, actorUserId);
        groupActivityService.log(groupId, actorUserId, actorName,
                ActivityType.GROUP_CREATED,
                "@" + actorName + " updated group details",
                groupId);
        return group;
    }


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

    @CacheEvict(value = {"group-details", "group-balances", "group-expenses"}, key = "#groupId")
    public void deleteGroup(Long groupId, UUID actorUserId) {
        ExpenseGroup group = groupRepo.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));
        requireGroupMember(groupId, actorUserId);

        GroupBalanceResponse balances = self.getGroupBalances(groupId);
        boolean hasDebt = balances.getMemberBalances().stream()
                .anyMatch(b -> b.getNetBalance().compareTo(BigDecimal.ZERO) != 0);

        if (hasDebt) {
            throw new IllegalArgumentException("Cannot delete group: all debts must be settled first.");
        }

        group.setDeleted(true);
        groupRepo.save(group);

        List<GroupMember> members = memberRepo.findByGroupId(groupId);
        if (cacheManager.getCache("user-groups") != null) {
            members.forEach(m -> Objects.requireNonNull(cacheManager.getCache("user-groups")).evict(m.getUserId()));
        }
        if (cacheManager.getCache("group-activity") != null) {
            Objects.requireNonNull(cacheManager.getCache("group-activity")).evict(groupId);
        }
        cacheEvictPublisher.publishForUsers(
                members.stream().map(GroupMember::getUserId).collect(Collectors.toSet()), 
                "GROUP_DELETED", 
                groupId);
        
        Set<UUID> memberIds = members.stream().map(GroupMember::getUserId).collect(Collectors.toSet());
        evictUserGroupsForMembers(groupId);
        queryCacheEvictor.evictGroupKeys(groupId, memberIds);
    }

    @Caching(evict = {
        @CacheEvict(value = "user-groups", key = "#userId"),
        @CacheEvict(value = "group-details", key = "#groupId + '-' + #userId")
    })
    public void archiveGroup(Long groupId, UUID userId, boolean archive) {
        GroupMember member = memberRepo.findByGroupId(groupId).stream()
                .filter(m -> m.getUserId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Membership not found"));
        member.setArchived(archive);
        memberRepo.save(member);
        cacheEvictPublisher.publishForUsers(Set.of(userId), "GROUP_ARCHIVED", groupId);
        evictUserGroupsForMembers(groupId);
        queryCacheEvictor.evictGroupKeys(groupId, Set.of(userId));
    }

    /* ─── MEMBERS ─── */

    @CacheEvict(value = "user-groups", key = "#req.userId")
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
        groupRepo.findById(groupId).ifPresent(group -> notificationService.sendNotification(req.getUserId(), Map.of(
                "status", "INFO",
                "message", "You've been invited to join the group: " + group.getName(),
                "event", "group-invite",
                "groupId", groupId)));
                
        cacheEvictPublisher.publishForUsers(Set.of(req.getUserId()), "MEMBER_ADDED", groupId);
        evictUserGroupsForMembers(groupId);
        queryCacheEvictor.evictGroupKeys(groupId, Set.of(req.getUserId()));
        return member;
    }

    private GroupMember addMemberInternal(Long groupId, UUID userId, String name) {
        GroupMember m = new GroupMember();
        m.setGroup(groupRepo.getReferenceById(groupId));
        m.setUserId(userId);
        m.setName(name);
        m.setStatus(GroupMember.InvitationStatus.PENDING);
        return memberRepo.save(m);
    }

    @Caching(evict = {
            @CacheEvict(value = "user-groups", key = "#userId"),
            @CacheEvict(value = "group-details", key = "#groupId + '-' + #userId")
    })
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
        cacheEvictPublisher.publishForUsers(Set.of(userId), "MEMBER_ACCEPTED", groupId);
        evictUserGroupsForMembers(groupId);
        queryCacheEvictor.evictGroupKeys(groupId, Set.of(userId));
    }

    @Caching(evict = {
            @CacheEvict(value = "user-groups", key = "#userId"),
            @CacheEvict(value = "group-details", key = "#groupId + '-' + #userId")
    })
    public void rejectInvitation(Long groupId, UUID userId) {
        GroupMember member = memberRepo.findByGroupId(groupId).stream()
                .filter(m -> m.getUserId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Membership not found"));

        if (member.getStatus() == GroupMember.InvitationStatus.ACCEPTED) {
            throw new IllegalArgumentException(
                    "Cannot reject an already accepted invitation. Use leave group instead.");
        }

        memberRepo.delete(member);
        cacheEvictPublisher.publishForUsers(Set.of(userId), "MEMBER_REJECTED", groupId);
        evictUserGroupsForMembers(groupId);
        queryCacheEvictor.evictGroupKeys(groupId, Set.of(userId));
    }

    @Caching(evict = {
            @CacheEvict(value = "user-groups", key = "#userId"),
            @CacheEvict(value = "group-details", key = "#groupId + '-' + #userId")
    })
    public void leaveGroup(Long groupId, UUID userId) {
        GroupMember member = memberRepo.findByGroupId(groupId).stream()
                .filter(m -> m.getUserId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Membership not found"));

        // Ensure balance is 0
        GroupBalanceResponse balances = self.getGroupBalances(groupId);
        boolean hasBalance = balances.getMemberBalances().stream()
                .filter(b -> b.getUserId().equals(userId))
                .anyMatch(b -> b.getNetBalance().compareTo(BigDecimal.ZERO) != 0);

        if (hasBalance) {
            throw new IllegalArgumentException(
                    "Cannot leave the group with a non-zero balance. Please settle your debts first.");
        }

        memberRepo.delete(member);

        String actorName = member.getName();
        groupActivityService.log(groupId, userId, actorName,
                ActivityType.MEMBER_ADDED, // Reuse ActivityType, maybe we should add a LEAVE type, but reusing is fine
                                           // for now
                "@" + actorName + " left the group",
                member.getId());
        cacheEvictPublisher.publishForUsers(Set.of(userId), "MEMBER_LEFT", groupId);
        evictUserGroupsForMembers(groupId);
        queryCacheEvictor.evictGroupKeys(groupId, Set.of(userId));
    }

    @Transactional(readOnly = true)
    public List<GroupMember> getGroupMembers(Long groupId) {
        return memberRepo.findByGroupId(groupId);
    }

    /* ─── SHARED EXPENSES ─── */

    @Caching(evict = {
        @CacheEvict(value = "group-expenses", key = "#req.groupId"),
        @CacheEvict(value = "group-balances", key = "#req.groupId")
    })
    public SharedExpense addSharedExpense(CreateSharedExpenseRequest req, UUID actorUserId) {
        ExpenseGroup group = groupRepo.findById(req.getGroupId())
                .orElseThrow(() -> new IllegalArgumentException("Group " + req.getGroupId() + " not found"));

        List<GroupMember> allMembers = memberRepo.findByGroupId(req.getGroupId());

        GroupMember actorMember = allMembers.stream()
                .filter(m -> m.getUserId().equals(actorUserId))
                .findFirst()
                .orElseThrow(() -> new SecurityException("User is not a member of this group"));

        boolean payerIsMember = allMembers.stream().anyMatch(m -> m.getUserId().equals(req.getPaidBy()));
        if (!payerIsMember) {
            throw new SecurityException("Payer is not a member of this group");
        }

        String actorName = actorMember.getName();

        SharedExpense expense = new SharedExpense();
        expense.setGroup(groupRepo.getReferenceById(req.getGroupId()));
        expense.setDescription(req.getDescription());
        expense.setAmount(req.getAmount());
        expense.setCurrency(req.getCurrency() != null ? req.getCurrency() : group.getCurrency());
        expense.setPaidBy(req.getPaidBy());
        expense.setSplitType(req.getSplitType() != null
                ? SharedExpense.SplitType.valueOf(req.getSplitType().toUpperCase())
                : SharedExpense.SplitType.EQUAL);
        expense.setExpenseDate(req.getExpenseDate());
        expense.setReceiptUrl(req.getReceiptUrl());
        if (req.getExpenseCategory() != null && !req.getExpenseCategory().isBlank()) {
            try {
                expense.setExpenseCategory(
                        SharedExpense.ExpenseCategory.valueOf(req.getExpenseCategory().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
            }
        }
        expense = expenseRepo.save(expense);

        List<ExpenseSplit> splits = generateSplits(expense, req, allMembers); // see Fix #7
        createPersonalTransactions(expense, splits, group);
        evictAnalyticsCacheForSplits(splits, expense.getId(), "SPLIT_EXPENSE", expense.getPaidBy());

        ActivityType logType = expense.getSplitType() == SharedExpense.SplitType.SETTLEMENT
                ? ActivityType.SETTLEMENT_RECORDED
                : ActivityType.EXPENSE_ADDED;
        String logMessage = expense.getSplitType() == SharedExpense.SplitType.SETTLEMENT
                ? "@" + actorName + " recorded payment: " + expense.getDescription()
                : "@" + actorName + " added expense \"" + expense.getDescription() + "\" (" + expense.getAmount() + " "
                  + expense.getCurrency() + ")";

        groupActivityService.log(req.getGroupId(), actorUserId, actorName, logType, logMessage, expense.getId());
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
                        "groupId", gid));
            }
        });

        evictUserGroupsForMembers(req.getGroupId());

        return expense;
    }

    @Caching(evict = {
        @CacheEvict(value = "group-expenses", key = "#groupId"),
        @CacheEvict(value = "group-balances", key = "#groupId")
    })
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
            transactionEntryService.deleteEntryInternal(link.getTransactionEntryId(), link.getUserId());
        }
        transactionLinkRepo.deleteBySharedExpenseId(expenseId);
        splitRepo.deleteAll(splits);
        expenseRepo.delete(expense);

        evictAnalyticsCacheForSplits(splits, expenseId, "SPLIT_EXPENSE_DELETE",expense.getPaidBy());

        String actorName = resolveMemberName(groupId, actorUserId);
        groupActivityService.log(groupId, actorUserId, actorName,
                ActivityType.EXPENSE_DELETED,
                "@" + actorName + " deleted expense \"" + expense.getDescription()
                        + "\" (" + expense.getAmount() + " " + expense.getCurrency() + ")",
                expenseId);
        log.info("Shared expense deleted: id={}, group={}", expenseId, groupId);
        evictUserGroupsForMembers(groupId);
    }

    private void createPersonalTransactions(SharedExpense expense, List<ExpenseSplit> splits, ExpenseGroup group) {
        Category category = toTransactionCategory(expense);
        String groupLabel = group.getName() != null ? group.getName() : "group";

        List<CreateEntryRequest> entries = new ArrayList<>();
        List<UUID> entryUserIds = new ArrayList<>();

        if (expense.getSplitType() == SharedExpense.SplitType.SETTLEMENT) {
            for (ExpenseSplit split : splits) {
                if (split.getAmount().compareTo(BigDecimal.ZERO) <= 0)
                    continue;

                CreateEntryRequest payerEntry = new CreateEntryRequest();
                payerEntry.setUserId(expense.getPaidBy());
                payerEntry.setName("Paid " + split.getUserName());
                payerEntry.setAmount(split.getAmount());
                payerEntry.setType(TransactionType.EXPENSE);
                payerEntry.setCurrency(expense.getCurrency());
                payerEntry.setCategory(Category.SETTLEMENT);
                payerEntry.setDescription("Settlement · " + groupLabel);
                entries.add(payerEntry);
                entryUserIds.add(expense.getPaidBy());

                CreateEntryRequest receiverEntry = new CreateEntryRequest();
                receiverEntry.setUserId(split.getUserId());
                receiverEntry.setName("Received from " + resolveMemberName(expense.getGroupId(), expense.getPaidBy()));
                receiverEntry.setAmount(split.getAmount());
                receiverEntry.setType(TransactionType.INCOME);
                receiverEntry.setCurrency(expense.getCurrency());
                receiverEntry.setCategory(Category.SETTLEMENT);
                receiverEntry.setDescription("Settlement · " + groupLabel);
                entries.add(receiverEntry);
                entryUserIds.add(split.getUserId());
            }
        } else {
            for (ExpenseSplit split : splits) {
                if (split.getAmount().compareTo(BigDecimal.ZERO) <= 0)
                    continue;

                CreateEntryRequest entry = new CreateEntryRequest();
                entry.setUserId(split.getUserId());
                entry.setName(expense.getDescription());
                entry.setAmount(split.getAmount());
                entry.setType(TransactionType.EXPENSE);
                entry.setCurrency(expense.getCurrency());
                entry.setCategory(category);
                entry.setDescription("Split expense · " + groupLabel);
                entries.add(entry);
                entryUserIds.add(split.getUserId());
            }
        }

        if (entries.isEmpty()) {
            return;
        }

        List<CreateEntryResponse> saved = transactionEntryService.createEntries(entries, false);

        if (saved.size() != entries.size()) {
            throw new IllegalStateException("Batch entry creation returned mismatched count: expected "
                    + entries.size() + " got " + saved.size());
        }

        List<ExpenseTransactionLink> links = new ArrayList<>();
        for (int i = 0; i < saved.size(); i++) {
            ExpenseTransactionLink link = new ExpenseTransactionLink();
            link.setSharedExpense(expense);
            link.setUserId(entryUserIds.get(i));
            link.setTransactionEntry(transactionEntryRepo.getReferenceById(saved.get(i).getId()));
            links.add(link);
        }
        transactionLinkRepo.saveAll(links);
    }

    private void evictAnalyticsCacheForSplits(List<ExpenseSplit> splits, Long referenceId, String operation, UUID payerId) {
        Set<UUID> affectedUsers = splits.stream()
                .filter(s -> s.getAmount().compareTo(BigDecimal.ZERO) > 0)
                .map(ExpenseSplit::getUserId)
                .collect(Collectors.toSet());
        if (payerId != null) affectedUsers.add(payerId);
        cacheEvictPublisher.publishForUsers(affectedUsers, operation, referenceId);
    }

    private Category toTransactionCategory(SharedExpense expense) {
        if (expense.getExpenseCategory() == null) {
            return Category.OTHERS;
        }
        return Category.valueOf(expense.getExpenseCategory().name());
    }

    private List<ExpenseSplit> generateSplits(SharedExpense expense, CreateSharedExpenseRequest req, List<GroupMember> allMembers) {
        List<GroupMember> members = allMembers.stream()
                .filter(m -> m.getStatus() == GroupMember.InvitationStatus.ACCEPTED)
                .collect(Collectors.toList());
        if (members.isEmpty())
            return List.of();

        return switch (expense.getSplitType()) {
            case EQUAL -> generateEqualSplits(expense, members);
            case PERCENTAGE -> generatePercentageSplits(expense, req, members);
            case EXACT, SETTLEMENT -> generateExactSplits(expense, req, members);
        };
    }

    private List<ExpenseSplit> generateEqualSplits(SharedExpense expense, List<GroupMember> members) {
        BigDecimal each = expense.getAmount()
                .divide(BigDecimal.valueOf(members.size()), 2, RoundingMode.HALF_UP);
        List<ExpenseSplit> splits = members.stream().map(m -> {
            ExpenseSplit s = new ExpenseSplit();
            s.setSharedExpense(expense);
            s.setUserId(m.getUserId());
            s.setUserName(m.getName());
            s.setAmount(each);
            s.setSettled(m.getUserId().equals(expense.getPaidBy()));
            return s;
        }).collect(Collectors.toList());
        return splitRepo.saveAll(splits);
    }
    private List<ExpenseSplit> generatePercentageSplits(SharedExpense expense,
                                                        CreateSharedExpenseRequest req,
                                                        List<GroupMember> members) {
        Map<UUID, BigDecimal> pctMap = req.getSplitDetails() == null ? Map.of()
                : req.getSplitDetails().stream()
                .collect(Collectors.toMap(d -> d.getUserId(), d -> d.getValue()));
        if (pctMap.isEmpty()) {
            return generateEqualSplits(expense, members);
        }
        List<ExpenseSplit> splits = members.stream().map(m -> {
            BigDecimal pct = pctMap.getOrDefault(m.getUserId(), BigDecimal.ZERO);
            BigDecimal share = expense.getAmount().multiply(pct)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            ExpenseSplit s = new ExpenseSplit();
            s.setSharedExpense(expense);
            s.setUserId(m.getUserId());
            s.setUserName(m.getName());
            s.setAmount(share);
            s.setSettled(m.getUserId().equals(expense.getPaidBy()));
            return s;
        }).collect(Collectors.toList());
        return splitRepo.saveAll(splits);
    }

    private List<ExpenseSplit> generateExactSplits(SharedExpense expense,
                                                   CreateSharedExpenseRequest req,
                                                   List<GroupMember> members) {
        if (req.getSplitDetails() == null || req.getSplitDetails().isEmpty()) {
            return generateEqualSplits(expense, members);
        }
        Map<UUID, CreateSharedExpenseRequest.SplitDetailRequest> detailMap = req.getSplitDetails().stream()
                .collect(Collectors.toMap(d -> d.getUserId(), d -> d));
        List<ExpenseSplit> splits = members.stream().map(m -> {
            CreateSharedExpenseRequest.SplitDetailRequest detail = detailMap.get(m.getUserId());
            BigDecimal share = detail != null ? detail.getValue() : BigDecimal.ZERO;
            ExpenseSplit s = new ExpenseSplit();
            s.setSharedExpense(expense);
            s.setUserId(m.getUserId());
            s.setUserName(m.getName());
            s.setAmount(share);
            s.setSettled(m.getUserId().equals(expense.getPaidBy()));
            return s;
        }).collect(Collectors.toList());
        return splitRepo.saveAll(splits);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "group-expenses", key = "#groupId + '-' + #page + '-' + #size", sync = true)
    public org.springframework.data.domain.Page<SharedExpense> getGroupExpenses(Long groupId, int page, int size) {
        return expenseRepo.findByGroupIdOrderByCreatedAtDesc(groupId, org.springframework.data.domain.PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<GroupActivity> getGroupActivity(Long groupId, int page, int size) {
        groupRepo.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group " + groupId + " not found"));
        return groupActivityService.getGroupActivity(groupId, page, size);
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

    /* ─── SETTLEMENT ─── */

    @Caching(evict = {
        @CacheEvict(value = "group-expenses", key = "#groupId"),
        @CacheEvict(value = "group-balances", key = "#groupId")
    })
    public void settleDebt(Long groupId, UUID fromUserId, UUID toUserId, UUID actorUserId) {
        requireGroupMember(groupId, actorUserId);

        GroupBalanceResponse balances = self.getGroupBalances(groupId);

        // Calculate how much fromUserId actually owes to toUserId based on suggestions
        BigDecimal settledTotal = balances.getSimplifiedDebts().stream()
                .filter(s -> s.getFromUserId().equals(fromUserId) && s.getToUserId().equals(toUserId))
                .map(GroupBalanceResponse.SettlementSuggestion::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (settledTotal.compareTo(BigDecimal.ZERO) <= 0) {
            return; // No debt to settle
        }

        String fromName = resolveMemberName(groupId, fromUserId);
        String toName = resolveMemberName(groupId, toUserId);

        CreateSharedExpenseRequest req = new CreateSharedExpenseRequest();
        req.setGroupId(groupId);
        req.setDescription("Payment from " + fromName + " to " + toName);
        req.setAmount(settledTotal);
        req.setCurrency(balances.getSimplifiedDebts().stream().findFirst()
                .map(GroupBalanceResponse.SettlementSuggestion::getCurrency).orElse("INR"));
        req.setPaidBy(fromUserId);
        req.setSplitType("SETTLEMENT");
        req.setExpenseCategory("SETTLEMENT");

        CreateSharedExpenseRequest.SplitDetailRequest detail = new CreateSharedExpenseRequest.SplitDetailRequest();
        detail.setUserId(toUserId);
        detail.setValue(settledTotal);
        req.setSplitDetails(List.of(detail));

        addSharedExpense(req, actorUserId);

        // Push notification
        UUID targetNotificationUser = fromUserId.equals(actorUserId) ? toUserId : fromUserId;
        notificationService.sendNotification(targetNotificationUser, Map.of(
                "status", "SUCCESS",
                "message",
                fromName + " recorded a ₹" + settledTotal.setScale(2, RoundingMode.HALF_UP) + " settlement with "
                        + toName,
                "event", "debt-settled",
                "groupId", groupId));
    }

    private void requireGroupMember(Long groupId, UUID userId) {
        if (!memberRepo.existsByGroupIdAndUserId(groupId, userId)) {
            throw new SecurityException("User is not a member of this group");
        }
    }

    private String resolveMemberName(Long groupId, UUID userId) {
        return memberRepo.findByGroupIdAndUserId(groupId, userId)
                .map(GroupMember::getName)
                .orElseGet(() -> userValidationService.displayName(
                        userValidationService.requireRegisteredUser(userId)));
    }

    private void evictUserGroupsForMembers(Long groupId) {
        var members = memberRepo.findByGroupId(groupId);
        // Evict own user-groups cache
        var cache = cacheManager.getCache("user-groups");
        if (cache != null) {
            members.forEach(m -> cache.evict(m.getUserId()));
        }
        // Directly evict query-service user-groups keys — same Redis, no pub/sub needed
        Set<UUID> memberIds = members.stream().map(GroupMember::getUserId).collect(Collectors.toSet());
        queryCacheEvictor.evictUserGroupsKeys(memberIds);
    }
}
