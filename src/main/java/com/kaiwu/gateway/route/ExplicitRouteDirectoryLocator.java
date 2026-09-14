package com.kaiwu.gateway.route;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.yaml.snakeyaml.Yaml;
import reactor.core.publisher.Flux;

/** 从目录读取逐条显式声明的 Gateway 路由。 */
class ExplicitRouteDirectoryLocator implements RouteDefinitionLocator {

    private static final Logger log = LoggerFactory.getLogger(ExplicitRouteDirectoryLocator.class);

    private final Path directory;
    private final boolean strict;
    private final String sourceName;

    ExplicitRouteDirectoryLocator(Path directory, boolean strict, String sourceName) {
        this.directory = directory;
        this.strict = strict;
        this.sourceName = sourceName;
    }

    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        if (!Files.isDirectory(directory)) {
            if (strict) {
                throw new IllegalStateException(sourceName + "路由目录不存在：" + directory);
            }
            return Flux.empty();
        }
        return Flux.fromIterable(load());
    }

    private List<RouteDefinition> load() {
        List<RouteDefinition> definitions = new ArrayList<>();
        Set<String> routeIds = new HashSet<>();
        for (Path file : routeFiles()) {
            try {
                Object parsed = new Yaml().load(Files.readString(file));
                List<Object> entries = asList(parsed);
                if (strict && entries.isEmpty()) {
                    throw new IllegalArgumentException("路由文件不能为空");
                }
                for (Object entry : entries) {
                    RouteDefinition definition = toRouteDefinition(asMap(entry), file);
                    if (!routeIds.add(definition.getId())) {
                        throw new IllegalArgumentException("路由 id 重复：" + definition.getId());
                    }
                    definitions.add(definition);
                }
            } catch (IOException exception) {
                throw new UncheckedIOException("读取" + sourceName + "路由文件失败：" + file, exception);
            } catch (RuntimeException exception) {
                if (strict) {
                    throw new IllegalArgumentException(
                            sourceName + "路由文件无效：" + file + "（" + exception.getMessage() + "）", exception);
                }
                // 本地文件写错不阻断其它项目联调，但必须指出具体文件。
                log.warn("跳过无法解析的本地路由文件：{}（{}）", file, exception.getMessage());
            }
        }
        return definitions;
    }

    private List<Path> routeFiles() {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("列出" + sourceName + "路由目录失败：" + directory, exception);
        }
    }

    private RouteDefinition toRouteDefinition(Map<String, Object> source, Path file) {
        RouteDefinition definition = new RouteDefinition();
        definition.setId(requireText(source, "id", file));
        definition.setUri(URI.create(requireText(source, "uri", file)));
        for (Object predicate : asList(source.get("predicates"))) {
            definition.getPredicates().add(new PredicateDefinition(String.valueOf(predicate)));
        }
        if (definition.getPredicates().isEmpty()) {
            throw new IllegalArgumentException("缺少 predicates：" + file);
        }
        for (Object filter : asList(source.get("filters"))) {
            definition.getFilters().add(new FilterDefinition(String.valueOf(filter)));
        }
        Object metadata = source.get("metadata");
        if (metadata != null) {
            definition.setMetadata(asMap(metadata));
        }
        if (strict) {
            validateManagedProjectRoute(definition, file);
        }
        Object order = source.get("order");
        if (order instanceof Number number) {
            definition.setOrder(number.intValue());
        }
        log.info("载入{}路由 {}（来自 {}）", sourceName, definition.getId(), file.getFileName());
        return definition;
    }

    private static void validateManagedProjectRoute(RouteDefinition definition, Path file) {
        String scheme = definition.getUri().getScheme();
        if (!List.of("http", "https", "lb").contains(scheme)) {
            throw new IllegalArgumentException("受管业务路由 uri 只允许 http、https 或 lb：" + file);
        }
        if (definition.getUri().getHost() == null
                || definition.getUri().getHost().isBlank()) {
            throw new IllegalArgumentException("受管业务路由 uri 必须包含目标主机或服务名：" + file);
        }
        if (definition.getUri().getUserInfo() != null) {
            throw new IllegalArgumentException("受管业务路由 uri 不允许携带用户凭据：" + file);
        }
        Map<String, Object> metadata = definition.getMetadata();
        if (metadata == null || !"PROJECT".equals(String.valueOf(metadata.get("accessMode")))) {
            throw new IllegalArgumentException("受管业务路由必须声明 metadata.accessMode=PROJECT：" + file);
        }
        String audience = requireText(metadata, "audience", file);
        String projectCode = requireText(metadata, "projectCode", file);
        if (!projectCode.matches("[a-z][a-z0-9-]{1,62}")) {
            throw new IllegalArgumentException("受管业务路由 projectCode 格式错误：" + file);
        }
        if (!("kaiwu-" + projectCode + "-service").equals(audience)) {
            throw new IllegalArgumentException("受管业务路由 audience 与 projectCode 不匹配：" + file);
        }
    }

    private static String requireText(Map<String, Object> source, String key, Path file) {
        Object value = source.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("缺少 " + key + "：" + file);
        }
        return value.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        if (value == null) {
            return List.of();
        }
        return value instanceof List<?> list ? (List<Object>) list : List.of(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("路由定义必须是映射，实际：" + value);
    }
}
