package com.kaiwu.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class TrustedHeaderSanitizerFilterTest {

    @Test
    void removesClientSuppliedIdentityHeaders() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/hello")
                .header("X-Kaiwu-Context", "forged")
                .header("X-User-Id", "1")
                .header("Accept-Language", "en-US"));
        AtomicReference<org.springframework.web.server.ServerWebExchange> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = value -> {
            forwarded.set(value);
            return Mono.empty();
        };

        StepVerifier.create(new TrustedHeaderSanitizerFilter().filter(exchange, chain))
                .verifyComplete();

        assertThat(forwarded.get().getRequest().getHeaders().getFirst("X-Kaiwu-Context"))
                .isNull();
        assertThat(forwarded.get().getRequest().getHeaders().getFirst("X-User-Id"))
                .isNull();
        // locale 只影响展示内容，不是可信身份头，Gateway 必须原样透传。
        assertThat(forwarded.get().getRequest().getHeaders().getFirst("Accept-Language"))
                .isEqualTo("en-US");
    }
}
