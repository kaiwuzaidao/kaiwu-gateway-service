package com.kaiwu.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ApplicationRouteConfigurationTest {

    @Test
    void conversationGenerationSharesBoundedPostChatRoute() throws IOException {
        var source = new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("routes", new org.springframework.core.io.ClassPathResource("application-local.yml"))
                .getFirst();
        String prefix = "spring.cloud.gateway.server.webflux.routes[3].";
        assertThat(source.getProperty(prefix + "id")).isEqualTo("system-agent-ask");
        assertThat(source.getProperty(prefix + "predicates[1]")).isEqualTo("Method=POST");
        assertThat(source.getProperty(prefix + "metadata.response-timeout")).isEqualTo(150000);
        assertThat(source.getProperty(prefix + "metadata.accessMode")).isEqualTo("PLATFORM");
        assertThat(source.getProperty(prefix + "metadata.audience")).isEqualTo("kaiwu-system-service");
        String paths = source.getProperty(prefix + "predicates[0]").toString().substring("Path=".length());
        var predicate = new org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory(
                        new org.springframework.boot.webflux.autoconfigure.WebFluxProperties())
                .apply(new org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory.Config()
                        .setPatterns(java.util.List.of(paths.split(","))));
        for (String path :
                java.util.List.of("/api/agent/ask", "/api/agent/conversations/9000000000000000001/messages")) {
            assertThat(predicate.test(org.springframework.mock.web.server.MockServerWebExchange.from(
                            org.springframework.mock.http.server.reactive.MockServerHttpRequest.post(path))))
                    .isTrue();
        }
        for (String path : java.util.List.of(
                "/api/agent/conversations", "/api/agent/memories", "/api/agent/conversations/1/messages/extra")) {
            assertThat(predicate.test(org.springframework.mock.web.server.MockServerWebExchange.from(
                            org.springframework.mock.http.server.reactive.MockServerHttpRequest.post(path))))
                    .isFalse();
        }
    }

    @Test
    void platformRouteExplicitlyAllowsSchedulerManagementApis() throws IOException {
        try (var stream = getClass().getResourceAsStream("/application-local.yml")) {
            assertThat(stream).isNotNull();
            String yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(yaml).contains("/api/scheduler/**");
        }
    }

    @Test
    void platformRouteExplicitlyAllowsCurrentUserLocaleApi() throws IOException {
        try (var stream = getClass().getResourceAsStream("/application-local.yml")) {
            assertThat(stream).isNotNull();
            String yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            int routeStart = yaml.indexOf("- id: system-platform");
            assertThat(routeStart).isGreaterThanOrEqualTo(0);
            int nextRoute = yaml.indexOf("\n            - id:", routeStart + 1);
            String platformRoute = nextRoute < 0 ? yaml.substring(routeStart) : yaml.substring(routeStart, nextRoute);

            assertThat(platformRoute)
                    .contains("/api/auth/me/locale")
                    .contains("accessMode: PLATFORM")
                    .contains("audience: kaiwu-system-service")
                    .doesNotContain("/api/auth/**");
        }
    }

    @Test
    void publicCatalogUsesAnExactGetOnlyPublicRoute() throws IOException {
        try (var stream = getClass().getResourceAsStream("/application-local.yml")) {
            assertThat(stream).isNotNull();
            String yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            int start = yaml.indexOf("- id: system-public-i18n-catalog");
            int end = yaml.indexOf("\n            - id:", start + 1);
            String route = yaml.substring(start, end);
            assertThat(route)
                    .contains("Path=/api/public/i18n/catalog")
                    .contains("Method=GET")
                    .contains("accessMode: PUBLIC")
                    .doesNotContain("/api/public/**");
        }
    }
}
