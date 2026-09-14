package com.kaiwu.gateway.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.cloud.gateway.route.RouteDefinition;

class ManagedRouteDirectoryLocatorTest {

    @Test
    void loadsExplicitProjectRoute(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("order-center.yml"), validRoute("order-center"));

        List<RouteDefinition> routes = new ManagedRouteDirectoryLocator(directory)
                .getRouteDefinitions()
                .collectList()
                .block();

        assertThat(routes).singleElement().satisfies(route -> {
            assertThat(route.getId()).isEqualTo("order-center-project");
            assertThat(route.getMetadata())
                    .containsEntry("accessMode", "PROJECT")
                    .containsEntry("audience", "kaiwu-order-center-service")
                    .containsEntry("projectCode", "order-center");
        });
    }

    @Test
    void failsClosedWhenProjectMetadataIsMissing(@TempDir Path directory) throws Exception {
        Files.writeString(
                directory.resolve("broken.yml"),
                """
                - id: broken-project
                  uri: http://broken-service:8080
                  predicates:
                    - Path=/broken-api/**
                """);

        assertThatThrownBy(() -> new ManagedRouteDirectoryLocator(directory).getRouteDefinitions())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accessMode=PROJECT");
    }

    @Test
    void failsClosedOnDuplicateRouteIds(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("a.yml"), validRoute("order-center"));
        Files.writeString(directory.resolve("b.yml"), validRoute("order-center"));

        assertThatThrownBy(() -> new ManagedRouteDirectoryLocator(directory).getRouteDefinitions())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("路由 id 重复");
    }

    @Test
    void rejectsUnsupportedUriScheme(@TempDir Path directory) throws Exception {
        Files.writeString(
                directory.resolve("broken.yml"),
                validRoute("order-center").replace("http://kaiwu-order-center-service:8080", "file:///etc/passwd"));

        assertThatThrownBy(() -> new ManagedRouteDirectoryLocator(directory).getRouteDefinitions())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只允许 http、https 或 lb");
    }

    @Test
    void rejectsEmptyRouteFile(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("empty.yml"), "# 尚未配置\n");

        assertThatThrownBy(() -> new ManagedRouteDirectoryLocator(directory).getRouteDefinitions())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("路由文件不能为空");
    }

    @Test
    void rejectsUriWithoutTargetHost(@TempDir Path directory) throws Exception {
        Files.writeString(
                directory.resolve("broken.yml"),
                validRoute("order-center").replace("http://kaiwu-order-center-service:8080", "http:/missing-host"));

        assertThatThrownBy(() -> new ManagedRouteDirectoryLocator(directory).getRouteDefinitions())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须包含目标主机或服务名");
    }

    @Test
    void rejectsCredentialsEmbeddedInUri(@TempDir Path directory) throws Exception {
        Files.writeString(
                directory.resolve("broken.yml"),
                validRoute("order-center")
                        .replace("http://kaiwu-order-center-service:8080", "http://user:secret@service:8080"));

        assertThatThrownBy(() -> new ManagedRouteDirectoryLocator(directory).getRouteDefinitions())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许携带用户凭据");
    }

    @Test
    void configuredDirectoryMustExist(@TempDir Path directory) {
        assertThatThrownBy(() -> new ManagedRouteDirectoryLocator(directory.resolve("missing")).getRouteDefinitions())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("路由目录不存在");
    }

    private String validRoute(String projectCode) {
        return """
                - id: %1$s-project
                  uri: http://kaiwu-%1$s-service:8080
                  predicates:
                    - Path=/%1$s-api/**
                  filters:
                    - RewritePath=/%1$s-api/(?<segment>.*), /${segment}
                  metadata:
                    accessMode: PROJECT
                    audience: kaiwu-%1$s-service
                    projectCode: %1$s
                """
                .formatted(projectCode);
    }
}
