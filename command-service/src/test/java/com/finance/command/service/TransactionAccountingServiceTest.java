package com.finance.command.service;

import com.finance.command.model.*;
import com.finance.command.repository.ExpenseTransactionLinkRepository;
import com.finance.command.repository.TransactionEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransactionAccountingServiceTest {
    final TransactionEntryRepository repository = mock(TransactionEntryRepository.class);
    final ExpenseTransactionLinkRepository groups = mock(ExpenseTransactionLinkRepository.class);
    final TransactionAccountingService service = new TransactionAccountingService(repository, groups);
    final UUID user = UUID.randomUUID();
    TransactionEntry original;

    @BeforeEach void setup() {
        original = new TransactionEntry(user, "Groceries", new BigDecimal("200"), TransactionType.EXPENSE, "INR");
        original.setId(1L);
        original.setCategory(Category.GROCERIES);
        original.setSubcategory("Weekly shop");
        when(repository.findAccountingEntryForUpdate(1L, user)).thenReturn(Optional.of(original));
        when(repository.sumRecoveries(1L, null)).thenReturn(new BigDecimal("50"));
    }

    TransactionEntry recovery(String amount) {
        TransactionEntry e = new TransactionEntry(user, "Refund", new BigDecimal(amount), TransactionType.INCOME, "INR");
        e.setTransactionKind(TransactionKind.REFUND);
        e.setOriginalTransactionId(1L);
        e.setCategory(Category.OTHERS);
        return e;
    }

    @Test void partialRefundInheritsCategoryAndReducesSpending() {
        TransactionEntry e = recovery("100");
        service.validate(e);
        assertThat(e.getCategory()).isEqualTo(Category.GROCERIES);
        assertThat(e.getSubcategory()).isEqualTo("Weekly shop");
        assertThat(e.getReportingType()).isEqualTo(TransactionType.EXPENSE);
        assertThat(e.getReportingAmount()).isEqualByComparingTo("-100");
    }

    @Test void combinedRecoveriesCannotExceedOriginal() {
        TransactionEntry e = recovery("151");
        e.setTransactionKind(TransactionKind.REIMBURSEMENT);
        assertThatThrownBy(() -> service.validate(e)).hasMessageContaining("cannot exceed");
    }

    @Test void originalMustBelongToTheCaller() {
        when(repository.findAccountingEntryForUpdate(1L, user)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.validate(recovery("10"))).hasMessage("Original expense not found");
    }

    @Test void mismatchedCurrencyAndMovementOriginalAreRejected() {
        TransactionEntry e = recovery("10");
        e.setCurrency("USD");
        assertThatThrownBy(() -> service.validate(e)).hasMessageContaining("same currency");
        original.setCategory(Category.CREDIT_CARD_PAYMENT);
        assertThatThrownBy(() -> service.validate(recovery("10"))).hasMessageContaining("must be an expense");
    }

    @Test void originalWithRecoveriesCannotBeChangedOrDeleted() {
        assertThatThrownBy(() -> service.requireNoRecoveries(original)).hasMessageContaining("Remove linked refunds");
    }

    @Test void groupRefundMustBeHandledInItsGroup() {
        when(groups.existsByTransactionEntryId(1L)).thenReturn(true);
        assertThatThrownBy(() -> service.validate(recovery("10"))).hasMessageContaining("in its group");
    }

    @Test void refundsRequireLinkIncomingDirectionAndNoRecurrence() {
        TransactionEntry e = recovery("10");
        e.setOriginalTransactionId(null);
        assertThatThrownBy(() -> service.validate(e)).hasMessageContaining("linked to an expense");
        e.setOriginalTransactionId(1L);
        e.setRecurring(true);
        assertThatThrownBy(() -> service.validate(e)).hasMessageContaining("non-recurring");
        e.setRecurring(false);
        e.setType(TransactionType.EXPENSE);
        assertThatThrownBy(() -> service.validate(e)).hasMessageContaining("incoming payment");
    }

    @Test void legacyMovementsAndExplicitTransfersAreExcluded() {
        for (Category category : AccountingRules.MOVEMENT_CATEGORIES) {
            original.setCategory(category);
            assertThat(original.isReportable()).isFalse();
            assertThat(original.getReportingAmount()).isZero();
        }
        original.setCategory(Category.OTHERS);
        original.setTransactionKind(TransactionKind.TRANSFER);
        assertThat(original.isReportable()).isFalse();
    }
}
