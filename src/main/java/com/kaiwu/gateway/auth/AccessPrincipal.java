package com.kaiwu.gateway.auth;

/**
 * 已通过 Gateway 外部 Access JWT 验证的最小身份。
 */
public record AccessPrincipal(String userId, String sessionId, String username) {}
