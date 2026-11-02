package com.apigateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Spring Cloud Gateway proxies every route through a generic handler, so the server
 * observation never receives a path pattern and all proxied requests are tagged
 * {@code uri="UNKNOWN"}. That makes the notifications SSE stream indistinguishable
 * from normal API calls: its lifetime (minutes) is recorded as request latency and
 * can own the gateway's 99th percentile.
 *
 * <p>Tagging the stream with a constant path pattern gives Prometheus a low-cardinality
 * label to drop on (see {@code k8s/config/infra/prometheus-config.yaml}), so stream
 * lifetime never reaches the latency panels.
 */
@Configuration
public class SseObservationConfig {

    @Bean
    WebFilter notificationsObservationFilter() {
        return new NotificationsObservationFilter();
    }

    static final class NotificationsObservationFilter implements WebFilter, Ordered {

        /** The gateway route {@code command-service-sse} matches this prefix. */
        private static final String NOTIFICATIONS_PREFIX = "/api/upsert/notifications/";

        /** Constant, low-cardinality pattern recorded instead of "UNKNOWN". */
        private static final String NOTIFICATIONS_PATTERN = "/api/upsert/notifications/**";

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
            if (exchange.getRequest().getPath().value().startsWith(NOTIFICATIONS_PREFIX)) {
                // The context is created by HttpWebHandlerAdapter before the filter
                // chain runs; the convention is re-evaluated on stop, so the
                // pattern set here replaces the "UNKNOWN" uri tag.
                ServerRequestObservationContext.findCurrent(exchange.getAttributes())
                        .ifPresent(context -> context.setPathPattern(NOTIFICATIONS_PATTERN));
            }
            return chain.filter(exchange);
        }
    }

}
