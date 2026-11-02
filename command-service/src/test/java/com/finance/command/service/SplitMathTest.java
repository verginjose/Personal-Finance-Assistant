package com.finance.command.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SplitService — split arithmetic")
class SplitMathTest {

    private static BigDecimal sum(List<BigDecimal> shares) {
        return shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    @DisplayName("equal split allocates every paisa when the amount doesn't divide evenly")
    void equalSplitSumsToAmount() {
        List<BigDecimal> shares = SplitService.splitEqually(new BigDecimal("100.00"), 3);

        assertThat(shares).containsExactly(
                new BigDecimal("33.34"),
                new BigDecimal("33.33"),
                new BigDecimal("33.33"));
        assertThat(sum(shares)).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("equal split keeps an exact division unchanged")
    void equalSplitExactDivision() {
        List<BigDecimal> shares = SplitService.splitEqually(new BigDecimal("1234.50"), 10);

        assertThat(shares).hasSize(10).allSatisfy(s -> assertThat(s).isEqualByComparingTo("123.45"));
        assertThat(sum(shares)).isEqualByComparingTo("1234.50");
    }

    @Test
    @DisplayName("equal split with a single paisa does not invent or lose money")
    void equalSplitSmallestAmount() {
        List<BigDecimal> shares = SplitService.splitEqually(new BigDecimal("0.01"), 3);

        assertThat(sum(shares)).isEqualByComparingTo("0.01");
        assertThat(shares).filteredOn(s -> s.compareTo(BigDecimal.ZERO) > 0).hasSize(1);
    }

    @Test
    @DisplayName("percentage split allocates every paisa")
    void percentageSplitSumsToAmount() {
        List<BigDecimal> shares = SplitService.splitByPercentage(
                new BigDecimal("1234.56"),
                List.of(new BigDecimal("10"), new BigDecimal("20"),
                        new BigDecimal("30"), new BigDecimal("40")));

        assertThat(sum(shares)).isEqualByComparingTo("1234.56");
    }

    @Test
    @DisplayName("percentage split rounds with largest remainder, not independent HALF_UP")
    void percentageSplitUsesLargestRemainder() {
        // 10% of 10.05 is 1.005 -> 1.00 (floored), and the leftover paisa goes
        // to one member; independent HALF_UP would allocate 1.01 four times and
        // overshoot the expense.
        List<BigDecimal> shares = SplitService.splitByPercentage(
                new BigDecimal("10.05"),
                List.of(new BigDecimal("10"), new BigDecimal("10"),
                        new BigDecimal("10"), new BigDecimal("70")));

        assertThat(sum(shares)).isEqualByComparingTo("10.05");
    }
}
