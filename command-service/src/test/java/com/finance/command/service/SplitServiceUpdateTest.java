package com.finance.command.service;

import com.finance.command.cache.QueryCacheEvictor;
import com.finance.command.dto.CreateEntryResponse;
import com.finance.command.dto.CreateSharedExpenseRequest;
import com.finance.command.model.*;
import com.finance.command.model.GroupActivity.ActivityType;
import com.finance.command.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cache.CacheManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SplitService — shared expense update")
class SplitServiceUpdateTest {

    @Mock ExpenseGroupRepository groupRepo;
    @Mock GroupMemberRepository memberRepo;
    @Mock SharedExpenseRepository expenseRepo;
    @Mock ExpenseSplitRepository splitRepo;
    @Mock ExpenseTransactionLinkRepository transactionLinkRepo;
    @Mock TransactionEntryRepository transactionEntryRepo;
    @Mock TransactionEntryService transactionEntryService;
    @Mock UserValidationService userValidationService;
    @Mock GroupActivityService groupActivityService;
    @Mock NotificationService notificationService;
    @Mock CacheManager cacheManager;
    @Mock QueryCacheEvictor queryCacheEvictor;

    @InjectMocks SplitService service;

    private final Long groupId = 7L;
    private final Long expenseId = 42L;
    private UUID actorId;
    private UUID otherId;
    private ExpenseGroup group;
    private SharedExpense expense;

    @BeforeEach
    void setUp() {
        actorId = UUID.randomUUID();
        otherId = UUID.randomUUID();

        group = new ExpenseGroup();
        group.setId(groupId);
        group.setName("Flat");
        group.setCurrency("INR");

        expense = new SharedExpense();
        expense.setId(expenseId);
        expense.setGroupId(groupId);
        expense.setDescription("Old dinner");
        expense.setAmount(new BigDecimal("100.00"));
        expense.setCurrency("INR");
        expense.setPaidBy(actorId);
        expense.setSplitType(SharedExpense.SplitType.EQUAL);
        expense.setExpenseCategory(SharedExpense.ExpenseCategory.RESTAURANTS);
        expense.setExpenseDate(LocalDateTime.now());
    }

    private GroupMember member(UUID userId, String name) {
        GroupMember m = new GroupMember();
        m.setGroupId(groupId);
        m.setUserId(userId);
        m.setName(name);
        m.setStatus(GroupMember.InvitationStatus.ACCEPTED);
        return m;
    }

    private ExpenseSplit split(UUID userId, String amount, boolean settled) {
        ExpenseSplit s = new ExpenseSplit();
        s.setSharedExpenseId(expenseId);
        s.setUserId(userId);
        s.setAmount(new BigDecimal(amount));
        s.setSettled(settled);
        return s;
    }

    private void stubCommon(List<GroupMember> members, List<ExpenseSplit> oldSplits) {
        when(groupRepo.findById(groupId)).thenReturn(java.util.Optional.of(group));
        when(memberRepo.existsByGroupIdAndUserId(groupId, actorId)).thenReturn(true);
        when(expenseRepo.findById(expenseId)).thenReturn(java.util.Optional.of(expense));
        when(memberRepo.findByGroupId(groupId)).thenReturn(members);
        when(splitRepo.findBySharedExpenseId(expenseId)).thenReturn(oldSplits);
        when(expenseRepo.save(any(SharedExpense.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(splitRepo.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionLinkRepo.findBySharedExpenseId(expenseId)).thenReturn(List.of());
    }

    @Test
    @DisplayName("rewrites the expense, regenerates splits and personal transactions")
    void updateSharedExpense_replacesSplitsAndTransactions() {
        List<GroupMember> members = List.of(member(actorId, "Jose"), member(otherId, "Sam"));
        List<ExpenseSplit> oldSplits = List.of(split(actorId, "50.00", true), split(otherId, "50.00", false));
        stubCommon(members, oldSplits);

        when(transactionEntryService.createEntries(anyList(), eq(false)))
                .thenReturn(List.of(new CreateEntryResponse(), new CreateEntryResponse()));
        when(transactionEntryRepo.getReferenceById(anyLong())).thenReturn(new TransactionEntry());

        CreateSharedExpenseRequest req = new CreateSharedExpenseRequest();
        req.setDescription("New dinner");
        req.setAmount(new BigDecimal("150.00"));
        req.setCurrency("INR");
        req.setPaidBy(otherId);
        req.setSplitType("EQUAL");
        req.setExpenseCategory("RESTAURANTS");
        req.setReceiptUrl("/receipts/new.png");

        SharedExpense result = service.updateSharedExpense(groupId, expenseId, req, actorId);

        assertThat(result.getDescription()).isEqualTo("New dinner");
        assertThat(result.getAmount()).isEqualByComparingTo("150.00");
        assertThat(result.getPaidBy()).isEqualTo(otherId);
        assertThat(result.getReceiptUrl()).isEqualTo("/receipts/new.png");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExpenseSplit>> splitsCaptor = ArgumentCaptor.forClass(List.class);
        verify(splitRepo).saveAll(splitsCaptor.capture());
        List<ExpenseSplit> newSplits = splitsCaptor.getValue();
        assertThat(newSplits).hasSize(2);
        assertThat(newSplits).extracting(ExpenseSplit::getAmount)
                .containsExactlyInAnyOrder(new BigDecimal("75.00"), new BigDecimal("75.00"));
        assertThat(newSplits.stream().filter(ExpenseSplit::isSettled).map(ExpenseSplit::getUserId))
                .containsExactly(otherId);

        verify(splitRepo).deleteAll(oldSplits);
        verify(transactionEntryService).createEntries(anyList(), eq(false));
        verify(groupActivityService).log(eq(groupId), eq(actorId), eq("Jose"),
                eq(ActivityType.EXPENSE_UPDATED), anyString(), eq(expenseId));
        verify(queryCacheEvictor).evictGroupKeys(eq(groupId), anySet());
    }

    @Test
    @DisplayName("refuses to edit an expense another member already settled")
    void updateSharedExpense_rejectsRecordedSettlements() {
        List<GroupMember> members = List.of(member(actorId, "Jose"), member(otherId, "Sam"));
        List<ExpenseSplit> oldSplits = List.of(split(actorId, "50.00", true), split(otherId, "50.00", true));
        stubCommon(members, oldSplits);

        CreateSharedExpenseRequest req = new CreateSharedExpenseRequest();
        req.setDescription("Changed");
        req.setAmount(new BigDecimal("150.00"));
        req.setPaidBy(actorId);
        req.setSplitType("EQUAL");

        assertThatThrownBy(() -> service.updateSharedExpense(groupId, expenseId, req, actorId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("recorded settlements");

        verify(expenseRepo, never()).save(any());
        verify(splitRepo, never()).deleteAll(anyList());
    }

    @Test
    @DisplayName("refuses to edit a settlement record")
    void updateSharedExpense_rejectsSettlement() {
        expense.setSplitType(SharedExpense.SplitType.SETTLEMENT);
        List<GroupMember> members = List.of(member(actorId, "Jose"), member(otherId, "Sam"));
        stubCommon(members, List.of());

        CreateSharedExpenseRequest req = new CreateSharedExpenseRequest();
        req.setDescription("Changed");
        req.setAmount(new BigDecimal("150.00"));
        req.setPaidBy(actorId);

        assertThatThrownBy(() -> service.updateSharedExpense(groupId, expenseId, req, actorId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Settlements cannot be edited");
    }
}
