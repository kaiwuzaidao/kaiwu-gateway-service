package com.kaiwu.gateway.auth;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

/**
 * Gateway 只读取 System 维护的 Redis 在线会话，不维护第二套身份数据。
 */
@Repository
public class SessionSnapshotRepository {

    private static final String SESSION_KEY_PREFIX = "kaiwu:session:";

    private final ReactiveStringRedisTemplate redisTemplate;

    public SessionSnapshotRepository(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Mono<SessionSnapshot> find(String sessionId) {
        return redisTemplate
                .opsForHash()
                .entries(SESSION_KEY_PREFIX + sessionId)
                .collectMap(entry -> entry.getKey().toString(), entry -> entry.getValue()
                        .toString())
                .filter(values -> StringUtils.hasText(values.get("userId")))
                .map(this::toSnapshot);
    }

    private SessionSnapshot toSnapshot(Map<String, String> values) {
        String rawPermissions = values.getOrDefault("permissions", "");
        Set<String> permissions = Arrays.stream(rawPermissions.split(","))
                .filter(StringUtils::hasText)
                .collect(Collectors.toUnmodifiableSet());
        return new SessionSnapshot(
                values.get("userId"), values.get("username"), permissions, values.get("authzVersion"));
    }
}
