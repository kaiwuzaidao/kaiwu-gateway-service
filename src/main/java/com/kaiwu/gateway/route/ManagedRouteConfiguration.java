package com.kaiwu.gateway.route;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 部署环境显式启用的受管业务路由目录；未配置时不装配。 */
@Configuration
@ConditionalOnProperty(name = "kaiwu.gateway.managed-routes-dir")
public class ManagedRouteConfiguration {

    @Bean
    public ManagedRouteDirectoryLocator managedRouteDirectoryLocator(
            @Value("${kaiwu.gateway.managed-routes-dir}") String directory) {
        return new ManagedRouteDirectoryLocator(Path.of(directory));
    }
}
