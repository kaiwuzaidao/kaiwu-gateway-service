package com.kaiwu.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RoutePolicyFilterTest {

    private final RoutePolicyFilter filter = new RoutePolicyFilter();

    @Test
    void allowsExplicitPublicRoute() {
        MockServerWebExchange exchange = exchange(Map.of(
                "accessMode", "PUBLIC",
                "audience", "kaiwu-system-service"));
        AtomicBoolean called = new AtomicBoolean();

        StepVerifier.create(filter.filter(exchange, markingChain(called))).verifyComplete();

        assertThat(called).isTrue();
    }

    @Test
    void allowsProtectedRouteToProceedToAuthenticationFilter() {
        MockServerWebExchange exchange = exchange(Map.of(
                "accessMode", "PLATFORM",
                "audience", "kaiwu-system-service"));
        AtomicBoolean called = new AtomicBoolean();

        StepVerifier.create(filter.filter(exchange, markingChain(called))).verifyComplete();

        assertThat(called).isTrue();
    }

    @Test
    void rejectsRouteWithoutSecurityMetadata() {
        MockServerWebExchange exchange = exchange(Map.of());
        AtomicBoolean called = new AtomicBoolean();

        StepVerifier.create(filter.filter(exchange, markingChain(called))).verifyComplete();

        assertThat(called).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private static MockServerWebExchange exchange(Map<String, Object> metadata) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/example"));
        Route route = Route.async()
                .id("test")
                .uri(URI.create("http://localhost"))
                .predicate(value -> true)
                .metadata(metadata)
                .build();
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);
        return exchange;
    }

    private static GatewayFilterChain markingChain(AtomicBoolean called) {
        return value -> {
            called.set(true);
            return Mono.empty();
        };
    }
}
