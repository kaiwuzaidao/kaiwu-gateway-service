package com.kaiwu.gateway.agent.service;

import com.kaiwu.gateway.auth.config.GatewayAuthProperties;
import io.jsonwebtoken.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;

/** 仅接受 System 服务用途断言；外部访问 JWT、注册凭据和普通 Context 均不能替代。 */
public final class AgentDelegationVerifier {
    private final PublicKey key;

    public AgentDelegationVerifier(GatewayAuthProperties properties) {
        try {
            String raw = properties
                    .getAccessPublicKey()
                    .replace("\\n", "\n")
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            key = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(raw)));
        } catch (Exception failure) {
            throw new IllegalStateException("Invalid System public key", failure);
        }
    }

    public String verify(String authorization, byte[] body) {
        return verify(authorization, body, "kaiwu-agent-delegation+jwt", "kaiwu-gateway-agent");
    }

    public String verifyWrite(String authorization, byte[] body) {
        return verify(authorization, body, "kaiwu-agent-write-delegation+jwt", "kaiwu-gateway-agent-write");
    }

    private String verify(String authorization, byte[] body, String type, String audience) {
        try {
            if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() > 8192)
                throw new IllegalArgumentException();
            var jwt = Jwts.parser()
                    .verifyWith(key)
                    .clockSkewSeconds(5)
                    .build()
                    .parseSignedClaims(authorization.substring(7));
            var c = jwt.getPayload();
            var now = Instant.now();
            if (!"RS256".equals(jwt.getHeader().getAlgorithm())
                    || !type.equals(jwt.getHeader().getType())
                    || !"kaiwu-system-service".equals(c.getIssuer())
                    || !"kaiwu-system-service".equals(c.getSubject())
                    || !Set.of(audience).equals(c.getAudience())
                    || c.getId() == null
                    || !c.getId().matches("[A-Za-z0-9-]{1,64}")
                    || c.getIssuedAt() == null
                    || c.getExpiration() == null
                    || c.getIssuedAt().toInstant().isAfter(now.plusSeconds(5))
                    || !c.getExpiration().after(c.getIssuedAt())
                    || !c.getExpiration().toInstant().isAfter(now)
                    || c.getExpiration()
                            .toInstant()
                            .isAfter(c.getIssuedAt().toInstant().plusSeconds(30))
                    || !digest(body).equals(c.get("bodyHash", String.class))) throw new IllegalArgumentException();
            return c.getId();
        } catch (RuntimeException failure) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED);
        }
    }

    public static String digest(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
