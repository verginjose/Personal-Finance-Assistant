package com.finance.query.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Provides a shared {@link HttpClient} bean for outbound HTTP calls
 * (currently used by {@link com.finance.query.service.AiInsightsService} for Groq API calls).
 *
 * <p>Exposing as a Spring bean allows:
 * <ul>
 *   <li>Reuse across services without per-class instantiation.</li>
 *   <li>Easy mocking in integration tests via {@code @MockBean}.</li>
 *   <li>Centralised timeout configuration via application properties.</li>
 * </ul>
 */
@Configuration
public class HttpClientConfig {

    @Value("${http.client.connect-timeout-seconds:10}")
    private int connectTimeoutSeconds;

    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build();
    }
}
