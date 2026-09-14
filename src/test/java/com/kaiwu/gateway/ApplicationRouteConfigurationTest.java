package com.kaiwu.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ApplicationRouteConfigurationTest {

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
