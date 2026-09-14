package com.kaiwu.gateway.route;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 本地开发用的路由目录（只在 {@code local} profile 装配）。
 *
 * <p>部署环境不存在这个 Bean：那里的路由只来自受管配置，不允许被工作目录里的文件影响。</p>
 */
@Configuration
@Profile("local")
public class LocalRouteConfiguration {

    @Bean
    public LocalRouteDirectoryLocator localRouteDirectoryLocator(
            @Value("${kaiwu.gateway.local-routes-dir:routes.local.d}") String directory) {
        return new LocalRouteDirectoryLocator(Path.of(directory));
    }
}
