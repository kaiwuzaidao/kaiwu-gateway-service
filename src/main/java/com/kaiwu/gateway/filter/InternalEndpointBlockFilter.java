package com.kaiwu.gateway.filter;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;

/**
 * `/internal` 任意路径段永远不允许从外部 Gateway 进入。
 */
@Component
public class InternalEndpointBlockFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        boolean internal =
                containsInternalSegment(exchange.getRequest().getURI().getRawPath());
        if (internal) {
            return GatewayResponses.reject(exchange, HttpStatus.NOT_FOUND, "资源不存在", "gateway.resourceNotFound");
        }
        return chain.filter(exchange);
    }

    private static boolean containsInternalSegment(String rawPath) {
        String decoded = rawPath;
        try {
            boolean stabilized = false;
            // 有限多轮解码覆盖嵌套编码；超过上限仍变化时按可疑路径失败关闭。
            for (int depth = 0; depth < 16; depth++) {
                String next = UriUtils.decode(decoded, StandardCharsets.UTF_8);
                if (next.equals(decoded)) {
                    stabilized = true;
                    break;
                }
                decoded = next;
            }
            if (!stabilized) return true;
        } catch (IllegalArgumentException exception) {
            return true;
        }
        return Arrays.stream(decoded.replace('\\', '/').split("/"))
                .map(segment -> segment.split(";", 2)[0])
                .anyMatch(segment -> "internal".equalsIgnoreCase(segment));
    }

    @Override
    public int getOrder() {
        return -350;
    }
}
