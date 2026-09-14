package com.kaiwu.gateway.filter;

import java.util.List;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 删除只能由 Gateway 产生的身份头和不可信代理头。
 */
@Component
public class TrustedHeaderSanitizerFilter implements GlobalFilter, Ordered {

    static final List<String> UNTRUSTED_HEADERS = List.of(
            "X-Kaiwu-Context",
            "X-User-Id",
            "X-User-Roles",
            "X-Permissions",
            "Forwarded",
            "X-Forwarded-For",
            "X-Forwarded-Host",
            "X-Forwarded-Proto");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerWebExchange sanitized = exchange.mutate()
                .request(builder -> builder.headers(headers -> UNTRUSTED_HEADERS.forEach(headers::remove)))
                .build();
        return chain.filter(sanitized);
    }

    @Override
    public int getOrder() {
        return -300;
    }
}
