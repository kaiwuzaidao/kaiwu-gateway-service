package com.kaiwu.gateway.agent.dto;

/** 没有 targetUrl/userId/projectId 字段；目标和身份只能来自权威授权结果。 */
public record AgentRemoteRequest(
        String sessionId, String code, String version, String manifestHash, String paramsJson, String correlationId) {
    public void validate() {
        if (sessionId == null
                || !sessionId.matches("[A-Za-z0-9-]{1,128}")
                || code == null
                || code.length() > 160
                || !code.matches("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+")
                || version == null
                || !version.matches("[0-9]+(?:\\.[0-9]+){0,2}")
                || version.length() > 16
                || manifestHash == null
                || !manifestHash.matches("[0-9a-f]{64}")
                || paramsJson == null
                || paramsJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16384
                || correlationId == null
                || !correlationId.matches("[A-Za-z0-9-]{1,64}"))
            throw new IllegalArgumentException("Invalid Agent request");
    }

    @Override
    public String toString() {
        return "AgentRemoteRequest[redacted]";
    }
}
