package com.kaiwu.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExternalAccessTokenVerifierTest {

    private KeyPair keyPair;
    private ExternalAccessTokenVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        GatewayAuthProperties properties = new GatewayAuthProperties();
        properties.setAccessPublicKey(pem(keyPair));
        verifier = new ExternalAccessTokenVerifier(properties);
    }

    @Test
    void verifiesSystemAccessToken() {
        AccessPrincipal principal =
                verifier.verify(token(Instant.now(), Instant.now().plusSeconds(60)));

        assertThat(principal.userId()).isEqualTo("1");
        assertThat(principal.sessionId()).isEqualTo("session-1");
    }

    @Test
    void rejectsExpiredAccessToken() {
        assertThatThrownBy(() -> verifier.verify(
                        token(Instant.now().minusSeconds(120), Instant.now().minusSeconds(60))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String token(Instant issuedAt, Instant expiresAt) {
        return Jwts.builder()
                .header()
                .type(ExternalAccessTokenVerifier.ACCESS_TOKEN_TYPE)
                .and()
                .issuer("kaiwu-system-service")
                .audience()
                .add("kaiwu-gateway-service")
                .and()
                .subject("1")
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .claim("sid", "session-1")
                .claim("username", "admin")
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private static String pem(KeyPair pair) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes())
                        .encodeToString(pair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }
}
