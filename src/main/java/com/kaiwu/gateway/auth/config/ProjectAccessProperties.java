package com.kaiwu.gateway.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PROJECT 路由的入口授权快照配置。
 *
 * <p>{@code systemBaseUrl} 和 {@code internalToken} 缺一不可；任一缺失时 PROJECT 请求
 * 直接失败关闭，不退化成放行——一个「配错了就变成不鉴权」的开关迟早会被配错。</p>
 */
@ConfigurationProperties(prefix = "kaiwu.gateway.project-access")
public class ProjectAccessProperties {

    private String systemBaseUrl;
    private String internalToken;

    /** 快照存活时间。短到成员变更能较快生效，长到不让 System 承担每请求流量。 */
    private long cacheTtlSeconds = 30;

    /** 数据范围过大（DATA_SCOPE_TOO_LARGE）这类「配置问题导致的拒绝」的缓存时间，短到管理员调整后很快恢复。 */
    private long dataScopeDenyCacheTtlSeconds = 5;

    /** 缓存条目上限，按 (userId, projectCode) 计。有界是为了不让缓存本身变成内存泄漏。 */
    private long cacheMaxEntries = 10_000;

    /** 调用 System 的超时。宁可快速失败关闭，也不要把 Gateway 线程挂在慢响应上。 */
    private long timeoutSeconds = 2;

    public String getSystemBaseUrl() {
        return systemBaseUrl;
    }

    public void setSystemBaseUrl(String systemBaseUrl) {
        this.systemBaseUrl = systemBaseUrl;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    public long getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public void setCacheTtlSeconds(long cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    /** 数据范围过大拒绝的缓存秒数。 */
    public long getDataScopeDenyCacheTtlSeconds() {
        return dataScopeDenyCacheTtlSeconds;
    }

    /** 设置数据范围过大拒绝的缓存秒数。 */
    public void setDataScopeDenyCacheTtlSeconds(long dataScopeDenyCacheTtlSeconds) {
        this.dataScopeDenyCacheTtlSeconds = dataScopeDenyCacheTtlSeconds;
    }

    public long getCacheMaxEntries() {
        return cacheMaxEntries;
    }

    public void setCacheMaxEntries(long cacheMaxEntries) {
        this.cacheMaxEntries = cacheMaxEntries;
    }

    public long getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
