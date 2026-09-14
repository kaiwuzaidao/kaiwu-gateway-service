package com.kaiwu.gateway.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.Jwts;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import org.springframework.util.StringUtils;

/**
 * 本地验证 System 签发给 Gateway 的 RS256 Access JWT。
 */
public final class ExternalAccessTokenVerifier {

    public static final String ACCESS_TOKEN_TYPE = "kaiwu-access+jwt";

    private final String accessIssuer;
    private final String accessAudience;
    private final long clockSkewSeconds;
    private final PublicKey publicKey;

    public ExternalAccessTokenVerifier(GatewayAuthProperties properties) {
        if (!StringUtils.hasText(properties.getAccessPublicKey())) {
            throw new IllegalStateException("缺少必需配置：kaiwu.gateway.auth.access-public-key");
        }
        if (!StringUtils.hasText(properties.getAccessIssuer())
                || !StringUtils.hasText(properties.getAccessAudience())) {
            throw new IllegalStateException("Access JWT issuer 与 audience 不能为空");
        }
        if (properties.getClockSkewSeconds() < 0) {
            throw new IllegalStateException("JWT 时钟偏差不得小于 0 秒");
        }
        this.accessIssuer = properties.getAccessIssuer();
        this.accessAudience = properties.getAccessAudience();
        this.clockSkewSeconds = properties.getClockSkewSeconds();
        this.publicKey = parsePublicKey(properties.getAccessPublicKey());
    }

    public AccessPrincipal verify(String token) {
        try {
            Jws<Claims> parsed = Jwts.parser()
                    .verifyWith(publicKey)
                    .clockSkewSeconds(clockSkewSeconds)
                    .build()
                    .parseSignedClaims(token);
            JwsHeader header = parsed.getHeader();
            Claims claims = parsed.getPayload();
            require("RS256".equals(header.getAlgorithm()));
            require(ACCESS_TOKEN_TYPE.equals(header.getType()));
            require(accessIssuer.equals(claims.getIssuer()));
            require(hasAudience(claims.get("aud"), accessAudience));
            require(StringUtils.hasText(claims.getSubject()));
            require(StringUtils.hasText(claims.get("sid", String.class)));
            return new AccessPrincipal(
                    claims.getSubject(), claims.get("sid", String.class), claims.get("username", String.class));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Access JWT 无效", exception);
        }
    }

    private static PublicKey parsePublicKey(String pem) {
        try {
            String normalized = pem.replace("\\n", "\n")
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(normalized)));
        } catch (Exception exception) {
            throw new IllegalStateException("kaiwu.gateway.auth.access-public-key 不是有效的 RSA 公钥", exception);
        }
    }

    private static boolean hasAudience(Object rawAudience, String required) {
        if (rawAudience instanceof String value) {
            return required.equals(value);
        }
        return rawAudience instanceof Collection<?> values && values.size() == 1 && values.contains(required);
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("Access JWT 声明错误");
        }
    }
}
