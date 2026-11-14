package com.kaiwu.gateway.auth.model;

import java.util.List;
import java.util.Map;

/**
 * 用户在某个业务项目下的入口授权（来自 System，Gateway 只读不产）。
 *
 * @param projectId        项目权威 ID；客户端提交的 X-Project-Id 只能与它比对
 * @param projectCode      项目编码，与 Route 绑定
 * @param permissions      项目内权限码
 * @param projectRoleCodes 项目内角色编码
 * @param dataScope        System 算好的数据范围快照（docs/DATA_SCOPE.md §4），Gateway 不解释其语义，
 *                         原样写入 Context 声明 {@code ds}；项目没有受控资源时为 {@code null}。
 *                         用保持键序的 Map 而不是 JSON 树类型，是为了让 JWT 序列化器能原样输出。
 */
public record ProjectAccess(
        String projectId,
        String projectCode,
        List<String> permissions,
        List<String> projectRoleCodes,
        Map<String, Object> dataScope) {

    /** 没有数据范围的授权，等价于改动前的四字段形态。 */
    public ProjectAccess(
            String projectId, String projectCode, List<String> permissions, List<String> projectRoleCodes) {
        this(projectId, projectCode, permissions, projectRoleCodes, null);
    }
}
