package com.kaiwu.gateway.auth.service;

import com.kaiwu.gateway.auth.config.GatewayAuthProperties;
import com.kaiwu.gateway.auth.model.AccessPrincipal;
import com.kaiwu.gateway.auth.model.ProjectAccess;
import com.kaiwu.gateway.auth.model.SessionSnapshot;
import io.jsonwebtoken.Jwts;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.util.StringUtils;

/**
 * 为唯一目标服务签发 60 秒内部 Context。
 */
public final class GatewayContextIssuer {

    public static final String CONTEXT_TYPE = "kaiwu-context+jwt";

    /** 完整 Context 的字符上限（ADR 0003 §1）。 */
    private static final int MAX_CONTEXT_CHARS = 16384;

    /** 签发出的 Context 超过 16 KiB；调用方必须拒绝请求，不得放行或裁剪声明。 */
    public static class ContextTooLargeException extends RuntimeException {
        /** @param length 实际 Context 字符数，仅用于异常消息 */
        public ContextTooLargeException(int length) {
            super("Gateway Context 超过上限：" + length + " > " + MAX_CONTEXT_CHARS);
        }
    }

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
                access.projectCode(),
                access.dataScope());
    }

    public String issue(AccessPrincipal principal, SessionSnapshot snapshot, String audience) {
        return build(principal, snapshot, audience, snapshot.permissions(), List.of(), null, null, null);
    }

    /** 固定 Agent Handler 的单次只读 Context，不可用于普通业务 API。 */
    public String issueForAgent(
            String userId,
            String sessionId,
            String audience,
            ProjectAccess access,
            String code,
            String version,
            String manifestHash,
            String paramsHash,
            String correlationId) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .header()
                .type("kaiwu-agent-action-context+jwt")
                .and()
                .issuer(contextIssuer)
                .audience()
                .add(audience)
                .and()
                .subject(userId)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .claim("sid", sessionId)
                .claim("projectId", access.projectId())
                .claim("projectCode", access.projectCode())
                .claim("permissions", access.permissions())
                .claim("projectRoleCodes", access.projectRoleCodes())
                .claim("capability", code)
                .claim("version", version)
                .claim("manifestHash", manifestHash)
                .claim("paramsHash", paramsHash)
                .claim("correlationId", correlationId)
                .claim("readOnly", true);
        if (access.dataScope() != null) builder.claim("ds", access.dataScope());
        String token = builder.signWith(privateKey, Jwts.SIG.RS256).compact();
        if (token.length() > MAX_CONTEXT_CHARS) throw new IllegalStateException("Agent Context exceeds limit");
        return token;
    }

    /** 单次写用途 Context；confirmed 只能来自 System 动作授权结果。 */
    public String issueForAgentWrite(
            String userId,
            String sessionId,
            String audience,
            ProjectAccess access,
            String operation,
            String code,
            String version,
            String manifestHash,
            String paramsHash,
            String preconditionsHash,
            String previewHash,
            String actionId,
            Long actionExpiresAt,
            String correlationId,
            boolean confirmed) {
        if (!("PREVIEW".equals(operation) || "EXECUTE".equals(operation) || "RESULT".equals(operation))
                || confirmed == "PREVIEW".equals(operation))
            throw new IllegalArgumentException("Invalid Agent write authorization");
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(60);
        if ("EXECUTE".equals(operation)) {
            if (actionExpiresAt == null || actionExpiresAt <= now.toEpochMilli())
                throw new IllegalArgumentException("Agent action expired");
            expiry = Instant.ofEpochMilli(Math.min(expiry.toEpochMilli(), actionExpiresAt));
        }
        var builder = Jwts.builder()
                .header()
                .type("kaiwu-agent-write-context+jwt")
                .and()
                .issuer(contextIssuer)
                .audience()
                .add(audience)
                .and()
                .subject(userId)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .claim("sid", sessionId)
                .claim("projectId", access.projectId())
                .claim("projectCode", access.projectCode())
                .claim("permissions", access.permissions())
                .claim("projectRoleCodes", access.projectRoleCodes())
                .claim("operation", operation)
                .claim("capability", code)
                .claim("version", version)
                .claim("manifestHash", manifestHash)
                .claim("paramsHash", paramsHash)
                .claim("preconditionsHash", preconditionsHash)
                .claim("previewHash", previewHash)
                .claim("actionId", actionId)
                .claim("actionExpiresAt", actionExpiresAt)
                .claim("correlationId", correlationId)
                .claim("readOnly", !"EXECUTE".equals(operation))
                .claim("confirmed", confirmed);
        if (access.dataScope() != null) builder.claim("ds", access.dataScope());
        String token = builder.signWith(privateKey, Jwts.SIG.RS256).compact();
        if (token.length() > MAX_CONTEXT_CHARS) throw new IllegalStateException("Agent Context exceeds limit");
        return token;
    }

    private String build(
            AccessPrincipal principal,
            SessionSnapshot snapshot,
            String audience,
            java.util.Collection<String> permissions,
            java.util.Collection<String> projectRoleCodes,
            String projectId,
            String projectCode,
            Map<String, Object> dataScope) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(contextTtlSeconds);
        var builder = Jwts.builder()
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
                .claim("projectCode", projectCode);
        if (dataScope != null) {
            // 没有数据范围时不写这个键，Context 与改动前字节结构一致。
            builder.claim("ds", dataScope);
        }
        String token = builder.signWith(privateKey, Jwts.SIG.RS256).compact();
        if (token.length() > MAX_CONTEXT_CHARS) {
            throw new ContextTooLargeException(token.length());
        }
        return token;
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
