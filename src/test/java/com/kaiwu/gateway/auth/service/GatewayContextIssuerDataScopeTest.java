package com.kaiwu.gateway.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaiwu.gateway.auth.config.GatewayAuthProperties;
import com.kaiwu.gateway.auth.model.AccessPrincipal;
import com.kaiwu.gateway.auth.model.ProjectAccess;
import com.kaiwu.gateway.auth.model.SessionSnapshot;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 数据范围声明 {@code ds} 的签发规则（docs/DATA_SCOPE.md §5）与体积余量实测。
 */
class GatewayContextIssuerDataScopeTest {

    private static KeyPair key;
    private static GatewayContextIssuer issuer;
    private static final AccessPrincipal PRINCIPAL = new AccessPrincipal("10001", "session-1", "alice");
    private static final SessionSnapshot SNAPSHOT = new SessionSnapshot("10001", "alice", Set.of(), "v1");

    /** 生成测试用 RSA 密钥并建立签发器。 */
    @BeforeAll
    static void keys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        key = generator.generateKeyPair();
        GatewayAuthProperties properties = new GatewayAuthProperties();
        properties.setContextPrivateKey(
                Base64.getEncoder().encodeToString(key.getPrivate().getEncoded()));
        issuer = new GatewayContextIssuer(properties);
    }

    /** 用测试公钥验签并取出声明。 */
    private static Map<String, Object> claims(String token) {
        return Jwts.parser()
                .verifyWith(key.getPublic())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** 生成指定数量、形如真实项目权限码的列表。 */
    private static List<String> permissions(int count) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add("module%02d:resource%02d:operation%02d".formatted(i % 20, i % 50, i));
        }
        return list;
    }

    /** 范围对象：depts 个部门（19 位 ID）、两个资源引用同一批部门，总字节恰好不超过上限。 */
    /** 三个资源的默认范围。 */
    private static Map<String, Object> scope(int departments) {
        return scope(departments, 3);
    }

    /** 同上，资源数可调，用来把总字节顶到接近 6144。 */
    private static Map<String, Object> scope(int departments, int resources) {
        List<String> depts = new ArrayList<>();
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < departments; i++) {
            depts.add("%019d".formatted(1_700_000_000_000_000_000L + i));
            all.add(i);
        }
        Map<String, Object> rules = new LinkedHashMap<>();
        for (int r = 0; r < resources; r++) {
            rules.put("crm:resource%02d".formatted(r), Map.of("set", all));
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("v", 1);
        value.put("primaryDeptId", depts.getFirst());
        value.put("depts", depts);
        value.put("rules", rules);
        value.put("fp", "0123456789abcdef");
        return value;
    }

    /** 没有 dataScope 时 Context 的声明集合与改动前完全一致，不出现 ds。 */
    @Test
    void contextWithoutDataScopeHasExactlyTheOldClaims() {
        String token = issuer.issueForProject(
                PRINCIPAL, SNAPSHOT, "demo-service", new ProjectAccess("1", "demo", List.of("a:b:c"), List.of("r")));
        assertThat(claims(token).keySet())
                .containsExactlyInAnyOrder(
                        "iss",
                        "aud",
                        "sub",
                        "jti",
                        "iat",
                        "exp",
                        "sid",
                        "permissions",
                        "projectRoleCodes",
                        "authzVersion",
                        "projectId",
                        "projectCode");
    }

    /** 有 dataScope 时原样写入 ds，其余声明不变。 */
    @Test
    void contextWithDataScopeCarriesDsVerbatim() {
        Map<String, Object> scope = scope(3);
        String token = issuer.issueForProject(
                PRINCIPAL,
                SNAPSHOT,
                "demo-service",
                new ProjectAccess("1", "demo", List.of("a:b:c"), List.of("r"), scope));
        assertThat(claims(token)).containsEntry("ds", scope).containsEntry("projectCode", "demo");
    }

    /** PLATFORM 身份 Context 不带 ds。 */
    @Test
    void platformContextNeverCarriesDs() {
        assertThat(claims(issuer.issue(PRINCIPAL, SNAPSHOT, "kaiwu-system-service")))
                .doesNotContainKey("ds");
    }

    /** 实测：6 KiB 范围加大权限集，完整 Context 的字符数与 16 KiB 余量，数字写入验证记录。 */
    @Test
    void measuredHeadroomWithMaximumDataScope() {
        // 128 个 19 位部门被 7 个资源完全重叠引用，字节数顶到接近 6144 的上限。
        Map<String, Object> max = scope(128, 7);
        int scopeBytes = tools.jackson.databind.json.JsonMapper.builder()
                .build()
                .writeValueAsString(max)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                .length;
        assertThat(scopeBytes).isBetween(5900, DataScopeClaim.MAX_BYTES);

        // origin/dev 现有项目里权限码最多的清单只有 4 个，这里用远大于现实的 150 个做压力。
        for (int count : new int[] {4, 150}) {
            String token = issuer.issueForProject(
                    PRINCIPAL,
                    SNAPSHOT,
                    "demo-service",
                    new ProjectAccess("9000000000000001001", "demo", permissions(count), List.of("r1", "r2"), max));
            System.out.printf(
                    "MEASURE ds=%d bytes, permissions=%d, context=%d chars, headroom=%d%n",
                    scopeBytes, count, token.length(), 16384 - token.length());
            assertThat(token.length()).isLessThan(16384);
        }
    }

    /** 合起来超过 16 KiB 时失败关闭，既不放行也不裁剪声明。 */
    @Test
    void refusesToIssueContextOverSixteenKiB() {
        assertThatThrownBy(() -> issuer.issueForProject(
                        PRINCIPAL,
                        SNAPSHOT,
                        "demo-service",
                        new ProjectAccess("1", "demo", permissions(600), List.of("r"), scope(128))))
                .isInstanceOf(GatewayContextIssuer.ContextTooLargeException.class);
    }

    /** 超过 6144 字节的范围在进入签发前就被拒绝，而不是被截断。 */
    @Test
    void dataScopeOverSixKiBIsRejectedNotTruncated() {
        assertThatThrownBy(() -> DataScopeClaim.fromObject(scope(300)))
                .isInstanceOf(DataScopeClaim.TooLargeException.class);
    }
}
