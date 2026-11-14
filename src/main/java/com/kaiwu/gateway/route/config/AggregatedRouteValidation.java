package com.kaiwu.gateway.route.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** 在 Gateway 聚合全部路由来源后检查唯一性，失败时不发布部分路由。 */
@Component
@ConditionalOnProperty(name = "kaiwu.gateway.managed-routes-dir")
public class AggregatedRouteValidation implements BeanPostProcessor {
    /** 包装框架聚合 locator；目录内部校验不能发现跨来源冲突。 */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!"routeDefinitionLocator".equals(beanName) || !(bean instanceof RouteDefinitionLocator locator))
            return bean;
        return (RouteDefinitionLocator)
                () -> locator.getRouteDefinitions().collectList().flatMapMany(routes -> {
                    validate(routes);
                    return Flux.fromIterable(routes);
                });
    }

    /** 在全部来源组成的快照上验证身份、受众和路径均不重复。 */
    private static void validate(List<RouteDefinition> routes) {
        Set<String> ids = new HashSet<>();
        List<ScopedValue> audiences = new ArrayList<>();
        List<ScopedValue> paths = new ArrayList<>();
        for (RouteDefinition route : routes) {
            if (route.getId() == null || !ids.add(route.getId())) throw new IllegalStateException("路由来源之间 id 冲突");
            boolean project = "PROJECT".equals(route.getMetadata().get("accessMode"));
            Object audience = route.getMetadata().get("audience");
            if (project && audience == null) throw new IllegalStateException("项目路由缺少 audience");
            if (audience != null) {
                String value = audience.toString();
                if (audiences.stream()
                        .anyMatch(
                                old -> (project || old.project()) && old.value().equals(value)))
                    throw new IllegalStateException("项目路由来源之间 audience 冲突");
                audiences.add(new ScopedValue(value, project));
            }
            route.getPredicates().stream()
                    .filter(predicate -> "Path".equals(predicate.getName()))
                    .flatMap(predicate -> predicate.getArgs().values().stream())
                    .filter(value -> value.startsWith("/"))
                    .forEach(path -> {
                        if (paths.stream().anyMatch(old -> (project || old.project()) && overlaps(old.value(), path)))
                            throw new IllegalStateException("项目路由来源之间 Path 冲突");
                        paths.add(new ScopedValue(path, project));
                    });
        }
    }
    /** 路由值及是否为项目边界；平台内部重复不属于本检查范围。 */
    private record ScopedValue(String value, boolean project) {}

    /** 对带通配符的路径按固定前缀保守判重，无法证明不相交时拒绝。 */
    private static boolean overlaps(String left, String right) {
        if (left.equals(right)) return true;
        String leftPrefix = fixedPrefix(left);
        String rightPrefix = fixedPrefix(right);
        boolean wildcard = leftPrefix.length() != left.length() || rightPrefix.length() != right.length();
        return wildcard && (leftPrefix.startsWith(rightPrefix) || rightPrefix.startsWith(leftPrefix));
    }

    /** 截取第一个通配或模板变量之前的固定前缀。 */
    private static String fixedPrefix(String pattern) {
        int end = pattern.length();
        for (char marker : new char[] {'*', '?', '{'}) {
            int position = pattern.indexOf(marker);
            if (position >= 0) end = Math.min(end, position);
        }
        return pattern.substring(0, end);
    }
}
