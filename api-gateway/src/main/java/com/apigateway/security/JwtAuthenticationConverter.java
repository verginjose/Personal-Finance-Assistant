package com.apigateway.security;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class JwtAuthenticationConverter
        implements ServerAuthenticationConverter {

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        String token = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (token != null && token.startsWith("Bearer ")) {
            return Mono.just(new JwtAuthenticationToken(token.substring(7)));
        }
        
        // EventSource cannot set an Authorization header. The notification
        // stream is the only route that may authenticate from the query
        // string, and only with the short-lived, single-purpose ticket issued
        // by auth-service — never the access token itself.
        if (isNotificationStream(exchange)) {
            String ticket = exchange.getRequest().getQueryParams().getFirst("ticket");
            if (ticket != null && !ticket.isEmpty()) {
                return Mono.just(new JwtAuthenticationToken(ticket));
            }
        }

        return Mono.empty();
    }

    static boolean isNotificationStream(ServerWebExchange exchange) {
        return exchange.getRequest().getURI().getPath()
                .startsWith("/api/upsert/notifications/stream");
    }
}