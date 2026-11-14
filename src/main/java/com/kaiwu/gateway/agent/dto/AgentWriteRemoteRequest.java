package com.kaiwu.gateway.agent.dto;

import java.nio.charset.StandardCharsets;

/** System 冻结的写调用载荷；身份、项目、目标地址和确认标记均不由调用方提供。 */
public record AgentWriteRemoteRequest(
        String sessionId,
        String operation,
        String code,
        String version,
        String manifestHash,
        String paramsJson,
        String preconditionsJson,
        String previewHash,
        String actionId,
        Long actionExpiresAt,
        String correlationId) {
    public void validate() {
        if (sessionId == null
                || !sessionId.matches("[A-Za-z0-9-]{1,128}")
                || code == null
                || code.length() > 160
                || !code.matches("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+")
                || version == null
                || version.length() > 16
                || !version.matches("[0-9]+(?:\\.[0-9]+){0,2}")
                || !hash(manifestHash)
                || paramsJson == null
                || paramsJson.getBytes(StandardCharsets.UTF_8).length > 16_384
                || correlationId == null
                || !correlationId.matches("[A-Za-z0-9-]{1,64}"))
            throw new IllegalArgumentException("Invalid Agent write request");
        if ("PREVIEW".equals(operation)) {
            if (preconditionsJson != null || previewHash != null || actionId != null || actionExpiresAt != null)
                throw new IllegalArgumentException("Invalid Agent write preview");
        } else if ("EXECUTE".equals(operation) || "RESULT".equals(operation)) {
            if (preconditionsJson == null
                    || preconditionsJson.getBytes(StandardCharsets.UTF_8).length > 16_384
                    || !hash(previewHash)
                    || actionId == null
                    || !actionId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                    || actionExpiresAt == null
                    || actionExpiresAt <= 0) throw new IllegalArgumentException("Invalid Agent write action");
        } else {
            throw new IllegalArgumentException("Invalid Agent write operation");
        }
    }

    private static boolean hash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    @Override
    public String toString() {
        return "AgentWriteRemoteRequest[redacted]";
    }
}
