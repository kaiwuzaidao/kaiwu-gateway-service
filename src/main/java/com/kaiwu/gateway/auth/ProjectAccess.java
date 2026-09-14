package com.kaiwu.gateway.auth;

import java.util.List;

/**
 * 用户在某个业务项目下的入口授权（来自 System，Gateway 只读不产）。
 *
 * @param projectId        项目权威 ID；客户端提交的 X-Project-Id 只能与它比对
 * @param projectCode      项目编码，与 Route 绑定
 * @param permissions      项目内权限码
 * @param projectRoleCodes 项目内角色编码
 */
public record ProjectAccess(
        String projectId, String projectCode, List<String> permissions, List<String> projectRoleCodes) {}
