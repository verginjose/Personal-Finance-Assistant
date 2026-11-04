package com.finance.query.service;

import com.finance.query.model.RegisteredUser;
import com.finance.query.model.TransactionEntry;
import com.finance.query.repository.RegisteredUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Fills the transient {@code amountDefault} on ledger rows with the amount in
 * the user's default currency. The default comes from {@code auth.users} (the
 * same value auth-service hands the client), unless the caller passes an
 * explicit {@code targetCurrency}.
 *
 * <p>Conversion is best-effort: on any failure the field stays {@code null}
 * and the client keeps displaying the original amount as "not converted".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CurrencyConversionService {

    private final RegisteredUserRepository userRepository;
    private final FxRateService fxRateService;

    public void decorate(List<TransactionEntry> entries, UUID userId, String requestedTarget) {
        if (entries == null || entries.isEmpty()) return;
        String target = resolveTarget(userId, requestedTarget);
        if (target == null) return;
        for (TransactionEntry entry : entries) {
            if (entry == null) continue;
            entry.setAmountDefault(fxRateService.convert(entry.getAmount(), entry.getCurrency(), target));
        }
    }

    /**
     * @return the converted amount, or {@code null} when the target currency is
     *         unknown or no rate is available.
     */
    public BigDecimal amountDefaultFor(BigDecimal amount, String currency, UUID userId, String requestedTarget) {
        String target = resolveTarget(userId, requestedTarget);
        if (target == null) return null;
        return fxRateService.convert(amount, currency, target);
    }

    /**
     * The currency to convert into: the explicit request parameter when given,
     * otherwise the user's stored default. Returns {@code null} when the user
     * is unknown, so rows are left unconverted instead of being converted into
     * a currency the client is not expecting.
     */
    private String resolveTarget(UUID userId, String requested) {
        String explicit = normalize(requested);
        if (!explicit.isEmpty()) return explicit;
        if (userId == null) return null;

        try {
            // A missing row means the client's expected currency is unknown —
            // e.g. a freshly registered user not yet visible on the replica.
            // Guessing would mislabel the converted amount, so stay null.
            return userRepository.findById(userId)
                    .map(RegisteredUser::getDefaultCurrency)
                    .map(CurrencyConversionService::normalize)
                    .filter(currency -> !currency.isEmpty())
                    .orElse(null);
        } catch (RuntimeException e) {
            // The read model is a convenience: never fail a ledger read because
            // the default currency could not be looked up.
            log.warn("Could not resolve default currency for {}: {}", userId, e.toString());
            return null;
        }
    }

    private static String normalize(String currency) {
        return currency == null ? "" : currency.trim().toUpperCase();
    }
}
