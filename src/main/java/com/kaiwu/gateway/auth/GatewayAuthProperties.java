package com.kaiwu.gateway.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway 外部 JWT 验证与内部 Context 签发配置。
 */
@ConfigurationProperties(prefix = "kaiwu.gateway.auth")
public class GatewayAuthProperties {

    private String accessPublicKey;
    private String contextPrivateKey;
    private String accessIssuer = "kaiwu-system-service";
    private String accessAudience = "kaiwu-gateway-service";
    private String contextIssuer = "kaiwu-gateway-service";
    private long contextTtlSeconds = 60;
    private long clockSkewSeconds = 5;

    public String getAccessPublicKey() {
        return accessPublicKey;
    }

    public void setAccessPublicKey(String accessPublicKey) {
        this.accessPublicKey = accessPublicKey;
    }

    public String getContextPrivateKey() {
        return contextPrivateKey;
    }

    public void setContextPrivateKey(String contextPrivateKey) {
        this.contextPrivateKey = contextPrivateKey;
    }

    public String getAccessIssuer() {
        return accessIssuer;
    }

    public void setAccessIssuer(String accessIssuer) {
        this.accessIssuer = accessIssuer;
    }

    public String getAccessAudience() {
        return accessAudience;
    }

    public void setAccessAudience(String accessAudience) {
        this.accessAudience = accessAudience;
    }

    public String getContextIssuer() {
        return contextIssuer;
    }

    public void setContextIssuer(String contextIssuer) {
        this.contextIssuer = contextIssuer;
    }

    public long getContextTtlSeconds() {
        return contextTtlSeconds;
    }

    public void setContextTtlSeconds(long contextTtlSeconds) {
        this.contextTtlSeconds = contextTtlSeconds;
    }

    public long getClockSkewSeconds() {
        return clockSkewSeconds;
    }

    public void setClockSkewSeconds(long clockSkewSeconds) {
        this.clockSkewSeconds = clockSkewSeconds;
    }
}
