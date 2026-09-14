package com.kaiwu.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class InternalEndpointBlockFilterTest {

    @Test
    void blocksInternalPathSegment() {
        for (String path : new String[] {
            "/api/internal/authz/project-access",
            "/api/INTERNAL/authz/project-access",
            "/api/internal;v=1/authz/project-access",
            "/api/%69nternal/authz/project-access",
            "/api/%2569nternal/authz/project-access",
            "/api/%5cinternal/authz/project-access",
            "/api/%" + "25".repeat(20) + "69nternal/authz/project-access"
        }) {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
            AtomicBoolean called = new AtomicBoolean();
            GatewayFilterChain chain = value -> {
                called.set(true);
                return Mono.empty();
            };

            StepVerifier.create(new InternalEndpointBlockFilter().filter(exchange, chain))
                    .verifyComplete();

            assertThat(called).as(path).isFalse();
            assertThat(exchange.getResponse().getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
