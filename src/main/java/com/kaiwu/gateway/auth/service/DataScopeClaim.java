package com.kaiwu.gateway.auth.service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 校验并规整 System 返回的数据范围，供 Context 的 {@code ds} 声明使用（docs/DATA_SCOPE.md §5）。
 *
 * <p>Gateway 不解释范围语义，只保证两件事：它是一个 JSON 对象；紧凑序列化后不超过 6144 字节。
 * 超限一律拒绝，不截断——截断等于悄悄放宽或收窄用户能看到的行。</p>
 */
public final class DataScopeClaim {

    /** 规范化紧凑 JSON（含 fp）的字节上限，与 System 的 §4.5 一致。 */
    public static final int MAX_BYTES = 6144;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 工具类，不实例化。 */
    private DataScopeClaim() {}

    /** 数据范围超过 {@link #MAX_BYTES}；调用方必须拒绝请求。 */
    public static class TooLargeException extends RuntimeException {
        /** @param bytes 实际紧凑序列化字节数，仅用于异常消息，不含部门 ID */
        public TooLargeException(int bytes) {
            super("数据范围超过上限：" + bytes + " > " + MAX_BYTES);
        }
    }

    /**
     * 把 System 响应里的 {@code dataScope} 变成可写入 JWT 的 Map。
     *
     * @param raw 响应中的原始值；缺失或 JSON null 表示项目没有受控资源
     * @return 保持键序的 Map，缺失时为 {@code null}
     * @throws IllegalStateException 值存在但不是 JSON 对象
     * @throws TooLargeException     紧凑序列化后超过上限
     */
    public static Map<String, Object> fromObject(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalStateException("数据范围必须是 JSON 对象");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return checked(copy);
    }

    /** 同 {@link #fromObject(Object)}，输入是已解析的 JSON 树（Agent 委托响应）。 */
    public static Map<String, Object> fromNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IllegalStateException("数据范围必须是 JSON 对象");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> copy = JSON.convertValue(node, LinkedHashMap.class);
        return checked(copy);
    }

    /** 按紧凑 JSON 的 UTF-8 字节数校验上限，超限抛 {@link TooLargeException}。 */
    private static Map<String, Object> checked(Map<String, Object> value) {
        int bytes = JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_BYTES) {
            throw new TooLargeException(bytes);
        }
        return value;
    }
}
