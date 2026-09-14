package com.kaiwu.gateway.auth;

import com.kaiwu.gateway.client.ServiceWebClientFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Gateway 认证组件装配。
 */
@Configuration
@EnableConfigurationProperties({GatewayAuthProperties.class, ProjectAccessProperties.class})
public class GatewayAuthConfiguration {

    @Bean
    public ExternalAccessTokenVerifier externalAccessTokenVerifier(GatewayAuthProperties properties) {
        return new ExternalAccessTokenVerifier(properties);
    }

    @Bean
    public GatewayContextIssuer gatewayContextIssuer(GatewayAuthProperties properties) {
        return new GatewayContextIssuer(properties);
    }

    /**
     * PROJECT 入口授权解析器。
     *
     * <p>配置缺失时**不装配**：没有这个 Bean，PROJECT 请求会被
     * {@code GatewayAuthenticationFilter} 直接 503 拒绝。这是有意的失败关闭——
     * 让「没配好」表现为拒绝服务，而不是表现为不鉴权。</p>
     *
     * <p>客户端由 {@link ServiceWebClientFactory} 按 {@code system-base-url} 的 scheme 装配
     * （ADR 0024）。该地址与 Gateway 路由的 {@code uri} 读同一个环境变量，两条出站路径必须
     * 支持同一组 scheme：只让路由支持 {@code lb://} 会造成 PUBLIC/PLATFORM 正常、PROJECT
     * 全部 503 的「部分可用」。</p>
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "kaiwu.gateway.project-access",
            name = {"system-base-url", "internal-token"})
    public ProjectAccessResolver projectAccessResolver(
            ProjectAccessProperties properties,
            ObjectProvider<ReactiveLoadBalancer.Factory<ServiceInstance>> loadBalancerFactory) {
        WebClient client = ServiceWebClientFactory.create(
                properties.getSystemBaseUrl(),
                java.time.Duration.ofSeconds(properties.getTimeoutSeconds()),
                loadBalancerFactory.getIfAvailable());
        return new ProjectAccessResolver(client, properties);
    }
}
