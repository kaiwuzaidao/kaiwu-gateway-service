package com.kaiwu.gateway.auth;

import java.util.Set;

/**
 * System 写入 Redis、Gateway 只读的在线会话权限快照。
 */
public record SessionSnapshot(String userId, String username, Set<String> permissions, String authzVersion) {
    public SessionSnapshot {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }
}
