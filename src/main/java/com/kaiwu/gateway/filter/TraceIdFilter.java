package com.kaiwu.gateway.filter;

import java.util.UUID;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 为每个入口请求建立可审计 traceId。
 */
@Component
public class TraceIdFilter implements GlobalFilter, Ordered {

    public static final String TRACE_HEADER = "X-Trace-Id";
    public static final String TRACE_ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";
    public static final String TRACE_CONTEXT_KEY = "kaiwu.traceId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(TRACE_HEADER);
        String traceId =
                valid(incoming) ? incoming : UUID.randomUUID().toString().replace("-", "");
        exchange.getAttributes().put(TRACE_ATTRIBUTE, traceId);
        // 下游 Servlet 服务也会回传 traceId；提交前覆盖可保证代理响应中只有一个同名 Header。
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(TRACE_HEADER, traceId);
            return Mono.empty();
        });
        ServerWebExchange mutated = exchange.mutate()
                .request(builder -> builder.headers(headers -> headers.set(TRACE_HEADER, traceId)))
                .build();
        return chain.filter(mutated).contextWrite(context -> context.put(TRACE_CONTEXT_KEY, traceId));
    }

    private static boolean valid(String value) {
        return StringUtils.hasText(value) && value.length() <= 64 && value.matches("[A-Za-z0-9._-]+");
    }

    @Override
    public int getOrder() {
        return -400;
    }
}
