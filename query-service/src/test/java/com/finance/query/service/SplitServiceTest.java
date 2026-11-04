package com.finance.query.service;

import com.finance.query.dto.GroupBalanceResponse;
import com.finance.query.model.ExpenseGroup;
import com.finance.query.model.ExpenseSplit;
import com.finance.query.model.GroupMember;
import com.finance.query.model.SharedExpense;
import com.finance.query.repository.ExpenseGroupRepository;
import com.finance.query.repository.ExpenseSplitRepository;
import com.finance.query.repository.GroupActivityRepository;
import com.finance.query.repository.GroupMemberRepository;
import com.finance.query.repository.SharedExpenseRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SplitService — per-user expense enrichment")
class SplitServiceTest {

    @Mock ExpenseGroupRepository groupRepo;
    @Mock GroupMemberRepository memberRepo;
    @Mock SharedExpenseRepository expenseRepo;
    @Mock ExpenseSplitRepository splitRepo;
    @Mock CacheManager cacheManager;
    @Mock GroupActivityRepository groupActivityRepo;

    @InjectMocks SplitService service;

    private UUID me;
    private UUID friend;

    @BeforeEach
    void setUp() {
        me = UUID.randomUUID();
        friend = UUID.randomUUID();
    }

    private SharedExpense expense(long id, BigDecimal amount, SharedExpense.SplitType type, UUID paidBy) {
        SharedExpense e = new SharedExpense();
        e.setId(id);
        e.setGroupId(5L);
        e.setDescription("Expense " + id);
        e.setAmount(amount);
        e.setCurrency("INR");
        e.setPaidBy(paidBy);
        e.setSplitType(type);
        return e;
    }

    private GroupMember member(UUID userId, String name) {
        GroupMember m = new GroupMember();
        m.setGroupId(5L);
        m.setUserId(userId);
        m.setName(name);
        return m;
    }

    private ExpenseSplit split(Long expenseId, UUID userId, BigDecimal amount) {
        ExpenseSplit s = new ExpenseSplit();
        s.setSharedExpenseId(expenseId);
        s.setUserId(userId);
        s.setAmount(amount);
        return s;
    }

    @Test
    @DisplayName("getGroupExpenses resolves the requesting user's share and payer name")
    void getGroupExpenses_enrichesShareAndPayer() {
        SharedExpense dinner = expense(10L, new BigDecimal("100.00"), SharedExpense.SplitType.EQUAL, friend);
        when(expenseRepo.findByGroupIdOrderByCreatedAtDesc(eq(5L), eq(PageRequest.of(0, 20))))
                .thenReturn(new PageImpl<>(List.of(dinner)));
        when(splitRepo.findBySharedExpenseIdIn(List.of(10L)))
                .thenReturn(List.of(split(10L, me, new BigDecimal("25.00")),
                        split(10L, friend, new BigDecimal("75.00"))));
        when(memberRepo.findByGroupId(5L))
                .thenReturn(List.of(member(me, "Aarav"), member(friend, "Deepika")));

        var page = service.getGroupExpenses(5L, 0, 20, me);

        assertThat(page.getContent()).hasSize(1);
        SharedExpense enriched = page.getContent().get(0);
        assertThat(enriched.getMyShare()).isEqualByComparingTo("25.00");
        assertThat(enriched.getPaidByName()).isEqualTo("Deepika");
    }

    @Test
    @DisplayName("getGroupExpenses names the receiver of a settlement")
    void getGroupExpenses_resolvesSettlementCounterparty() {
        SharedExpense settlement = expense(20L, new BigDecimal("300.00"), SharedExpense.SplitType.SETTLEMENT, me);
        when(expenseRepo.findByGroupIdOrderByCreatedAtDesc(eq(5L), eq(PageRequest.of(0, 20))))
                .thenReturn(new PageImpl<>(List.of(settlement)));

        ExpenseSplit receiver = split(20L, friend, new BigDecimal("300.00"));
        receiver.setUserName("Deepika");
        when(splitRepo.findBySharedExpenseIdIn(List.of(20L))).thenReturn(List.of(receiver));
        when(memberRepo.findByGroupId(5L)).thenReturn(List.of(member(me, "Aarav"), member(friend, "Deepika")));

        var page = service.getGroupExpenses(5L, 0, 20, me);

        assertThat(page.getContent().get(0).getSettlementToName()).isEqualTo("Deepika");
    }

    @Test
    @DisplayName("getGroupBalances reports total spend excluding settlements")
    void getGroupBalances_totalSpendExcludesSettlements() {
        ExpenseGroup group = new ExpenseGroup();
        group.setId(5L);
        group.setName("Office lunch crew");
        group.setCurrency("INR");

        SharedExpense dinner = expense(10L, new BigDecimal("100.00"), SharedExpense.SplitType.EQUAL, me);
        SharedExpense settlement = expense(11L, new BigDecimal("300.00"), SharedExpense.SplitType.SETTLEMENT, me);

        when(groupRepo.findById(5L)).thenReturn(Optional.of(group));
        when(memberRepo.findByGroupId(5L)).thenReturn(List.of(member(me, "Aarav"), member(friend, "Deepika")));
        when(expenseRepo.findByGroupIdOrderByCreatedAtDesc(5L)).thenReturn(List.of(dinner, settlement));
        when(splitRepo.findBySharedExpenseIdIn(anyList())).thenReturn(List.of(
                split(10L, me, new BigDecimal("50.00")),
                split(10L, friend, new BigDecimal("50.00")),
                split(11L, friend, new BigDecimal("300.00"))));

        GroupBalanceResponse balances = service.getGroupBalances(5L);

        assertThat(balances.getTotalSpend()).isEqualByComparingTo("100.00");
        assertThat(balances.getMemberBalances()).hasSize(2);
        // totalPaid keeps the settlement for the net balance; totalSpent is the
        // figure "Spending by member" must show.
        assertThat(balances.getMemberBalances())
                .filteredOn(b -> me.equals(b.getUserId()))
                .singleElement()
                .satisfies(b -> {
                    assertThat(b.getTotalPaid()).isEqualByComparingTo("400.00");
                    assertThat(b.getTotalSpent()).isEqualByComparingTo("100.00");
                });
    }
}
