package com.finance.query.service;

import com.finance.query.model.RegisteredUser;
import com.finance.query.model.TransactionEntry;
import com.finance.query.repository.RegisteredUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CurrencyConversionService — Unit Tests")
class CurrencyConversionServiceTest {

    @Mock RegisteredUserRepository userRepository;
    @Mock FxRateService fxRateService;

    private CurrencyConversionService service;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        service = new CurrencyConversionService(userRepository, fxRateService);
    }

    private RegisteredUser user(String defaultCurrency) {
        RegisteredUser user = mock(RegisteredUser.class);
        // doReturn keeps this safe to call while another stubbing is in flight.
        lenient().doReturn(defaultCurrency).when(user).getDefaultCurrency();
        return user;
    }

    private TransactionEntry entry(String amount, String currency) {
        TransactionEntry entry = new TransactionEntry();
        entry.setAmount(new BigDecimal(amount));
        entry.setCurrency(currency);
        return entry;
    }

    @Test
    @DisplayName("converts into the user's stored default currency")
    void convertsIntoStoredDefaultCurrency() {
        RegisteredUser stored = user("inr");
        when(userRepository.findById(userId)).thenReturn(Optional.of(stored));
        when(fxRateService.convert(new BigDecimal("10.00"), "USD", "INR"))
                .thenReturn(new BigDecimal("835.00"));
        TransactionEntry usd = entry("10.00", "USD");

        service.decorate(List.of(usd), userId, null);

        assertThat(usd.getAmountDefault()).isEqualByComparingTo("835.00");
    }

    @Test
    @DisplayName("an explicit targetCurrency wins over the stored default")
    void explicitTargetWins() {
        when(fxRateService.convert(new BigDecimal("10.00"), "USD", "EUR"))
                .thenReturn(new BigDecimal("9.20"));
        TransactionEntry usd = entry("10.00", "USD");

        service.decorate(List.of(usd), userId, "eur");

        assertThat(usd.getAmountDefault()).isEqualByComparingTo("9.20");
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("leaves amountDefault null when the user is unknown")
    void unknownUserLeavesRowUnconverted() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        TransactionEntry usd = entry("10.00", "USD");

        // No auth.users row means the client's expected currency is unknown;
        // converting to a guess would mislabel the amount.
        service.decorate(List.of(usd), userId, null);

        assertThat(usd.getAmountDefault()).isNull();
        verifyNoInteractions(fxRateService);
    }

    @Test
    @DisplayName("leaves amountDefault null when the rate is unavailable")
    void missingRateLeavesRowUnconverted() {
        RegisteredUser stored = user("INR");
        when(userRepository.findById(userId)).thenReturn(Optional.of(stored));
        when(fxRateService.convert(any(), any(), any())).thenReturn(null);
        TransactionEntry usd = entry("10.00", "USD");

        service.decorate(List.of(usd), userId, null);

        assertThat(usd.getAmountDefault()).isNull();
    }

    @Test
    @DisplayName("a repository failure never fails the ledger read")
    void repositoryFailureIsSwallowed() {
        when(userRepository.findById(userId)).thenThrow(new IllegalStateException("replica down"));
        TransactionEntry usd = entry("10.00", "USD");

        service.decorate(List.of(usd), userId, null);

        assertThat(usd.getAmountDefault()).isNull();
        verifyNoInteractions(fxRateService);
    }

    @Test
    @DisplayName("amountDefaultFor returns the converted value for a single row")
    void amountDefaultForSingleRow() {
        RegisteredUser stored = user("INR");
        when(userRepository.findById(userId)).thenReturn(Optional.of(stored));
        when(fxRateService.convert(new BigDecimal("5.00"), "EUR", "INR"))
                .thenReturn(new BigDecimal("451.50"));

        assertThat(service.amountDefaultFor(new BigDecimal("5.00"), "EUR", userId, null))
                .isEqualByComparingTo("451.50");
    }

    @Test
    @DisplayName("empty or null pages are ignored")
    void emptyPageIsIgnored() {
        service.decorate(List.of(), userId, null);
        service.decorate(null, userId, null);

        verifyNoInteractions(userRepository, fxRateService);
    }
}
