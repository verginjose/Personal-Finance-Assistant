package com.finance.query.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FxRateService — Unit Tests")
class FxRateServiceTest {

    private static final String RATES_JSON = """
            {"amount":1.0,"base":"USD","date":"2026-11-01","rates":{"INR":83.5,"EUR":0.92}}
            """;

    @Mock HttpClient httpClient;

    private final AtomicInteger calls = new AtomicInteger();

    private FxRateService service(long ttlMinutes, long backoffSeconds) {
        return new FxRateService(httpClient, "https://rates.example/latest", ttlMinutes, backoffSeconds, 5);
    }

    private void stubOk() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        lenient().when(response.statusCode()).thenReturn(200);
        lenient().when(response.body()).thenReturn(RATES_JSON);
        doAnswer(invocation -> {
            calls.incrementAndGet();
            return response;
        }).when(httpClient).send(any(HttpRequest.class), any());
    }

    private void stubFailure() throws Exception {
        doAnswer(invocation -> {
            calls.incrementAndGet();
            throw new IOException("rates host unreachable");
        }).when(httpClient).send(any(HttpRequest.class), any());
    }

    @Test
    @DisplayName("converts with the published rate, rounded to two decimals")
    void convertsWithPublishedRate() throws Exception {
        stubOk();
        FxRateService fx = service(360, 60);

        assertThat(fx.convert(new BigDecimal("10.00"), "USD", "INR"))
                .isEqualByComparingTo(new BigDecimal("835.00"));
        assertThat(fx.convert(new BigDecimal("10.00"), "usd", "inr"))
                .isEqualByComparingTo(new BigDecimal("835.00"));
    }

    @Test
    @DisplayName("same currency needs no network call")
    void sameCurrencyShortCircuits() {
        FxRateService fx = service(360, 60);

        assertThat(fx.convert(new BigDecimal("42.129"), "INR", "INR"))
                .isEqualByComparingTo(new BigDecimal("42.13"));
        assertThat(calls).hasValue(0);
        verifyNoInteractions(httpClient);
    }

    @Test
    @DisplayName("caches the rate table across conversions")
    void cachesRatesBetweenCalls() throws Exception {
        stubOk();
        FxRateService fx = service(360, 60);

        fx.convert(BigDecimal.TEN, "USD", "INR");
        fx.convert(BigDecimal.ONE, "USD", "INR");

        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("returns null when the host fails and nothing is cached")
    void returnsNullOnFailureWithoutCache() throws Exception {
        stubFailure();
        FxRateService fx = service(360, 60);

        assertThat(fx.convert(BigDecimal.TEN, "USD", "INR")).isNull();
    }

    @Test
    @DisplayName("serves the last good snapshot when a refresh fails")
    void servesStaleRatesWhenRefreshFails() throws Exception {
        stubOk();
        FxRateService fx = service(0, 60); // every call refreshes
        assertThat(fx.convert(BigDecimal.TEN, "USD", "INR"))
                .isEqualByComparingTo(new BigDecimal("835.00"));

        reset(httpClient);
        stubFailure();

        assertThat(fx.convert(BigDecimal.TEN, "USD", "INR"))
                .isEqualByComparingTo(new BigDecimal("835.00"));
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("backs off after a failure instead of retrying on every row")
    void backsOffAfterFailure() throws Exception {
        stubFailure();
        FxRateService fx = service(360, 60);

        fx.convert(BigDecimal.TEN, "USD", "INR");
        fx.convert(BigDecimal.ONE, "USD", "INR");

        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("returns null for a currency the rates table does not list")
    void returnsNullForUnknownTarget() throws Exception {
        stubOk();
        FxRateService fx = service(360, 60);

        assertThat(fx.convert(BigDecimal.TEN, "USD", "XYZ")).isNull();
    }

    @Test
    @DisplayName("returns null on a non-200 response")
    void returnsNullOnErrorStatus() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(503);
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any());
        FxRateService fx = service(360, 60);

        assertThat(fx.convert(BigDecimal.TEN, "USD", "INR")).isNull();
    }
}
