package com.kaiwu.gateway.auth;

import io.jsonwebtoken.Jwts;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.util.StringUtils;

/**
 * 为唯一目标服务签发 60 秒内部 Context。
 */
public final class GatewayContextIssuer {

    public static final String CONTEXT_TYPE = "kaiwu-context+jwt";

    private final String contextIssuer;
    private final long contextTtlSeconds;
    private final PrivateKey privateKey;

    public GatewayContextIssuer(GatewayAuthProperties properties) {
        if (!StringUtils.hasText(properties.getContextPrivateKey())) {
            throw new IllegalStateException("缺少必需配置：kaiwu.gateway.auth.context-private-key");
        }
        if (!StringUtils.hasText(properties.getContextIssuer())) {
            throw new IllegalStateException("Gateway Context issuer 不能为空");
        }
        if (properties.getContextTtlSeconds() < 1 || properties.getContextTtlSeconds() > 60) {
            throw new IllegalStateException("Gateway Context 有效期必须在 1 到 60 秒之间");
        }
        this.contextIssuer = properties.getContextIssuer();
        this.contextTtlSeconds = properties.getContextTtlSeconds();
        this.privateKey = parsePrivateKey(properties.getContextPrivateKey());
    }

    /**
     * PROJECT 路由的 Context：额外带上项目事实与项目内权限。
     *
     * <p>{@code permissions} 换成项目内权限而不是平台权限——业务服务的
     * {@code @RequirePermission} 判定的是项目权限，混入平台权限会让平台管理员
     * 在任意业务项目里获得越权能力。</p>
     */
    public String issueForProject(
            AccessPrincipal principal, SessionSnapshot snapshot, String audience, ProjectAccess access) {
        return build(
                principal,
                snapshot,
                audience,
                access.permissions(),
                access.projectRoleCodes(),
                access.projectId(),
                access.projectCode());
    }

    public String issue(AccessPrincipal principal, SessionSnapshot snapshot, String audience) {
        return build(principal, snapshot, audience, snapshot.permissions(), List.of(), null, null);
    }

    private String build(
            AccessPrincipal principal,
            SessionSnapshot snapshot,
            String audience,
            java.util.Collection<String> permissions,
            java.util.Collection<String> projectRoleCodes,
            String projectId,
            String projectCode) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(contextTtlSeconds);
        return Jwts.builder()
                .header()
                .type(CONTEXT_TYPE)
                .and()
                .issuer(contextIssuer)
                .audience()
                .add(audience)
                .and()
                .subject(principal.userId())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .claim("sid", principal.sessionId())
                .claim("permissions", permissions)
                .claim("projectRoleCodes", projectRoleCodes)
                .claim("authzVersion", snapshot.authzVersion())
                .claim("projectId", projectId)
                .claim("projectCode", projectCode)
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    private static PrivateKey parsePrivateKey(String pem) {
        try {
            String normalized = pem.replace("\\n", "\n")
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(normalized)));
        } catch (Exception exception) {
            throw new IllegalStateException("kaiwu.gateway.auth.context-private-key 不是有效的 PKCS8 RSA 私钥", exception);
        }
    }
}
