package com.datanest.system.service;

import com.datanest.system.config.SsoProperties;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * IdP 组/claim → 平台角色 映射（Sprint 14，PRD D3/D4）。
 * <p>
 * 命中映射规则 → 返回规则角色（登录时覆盖用户当前角色）；
 * 未命中 → 返回空列表（由调用方决定：保留管理员手动调整，或使用默认角色）。
 */
@Service
public class RoleMappingService {

    private final SsoConfigService ssoConfigService;

    public RoleMappingService(SsoConfigService ssoConfigService) {
        this.ssoConfigService = ssoConfigService;
    }

    /** 按 claim 值列表匹配第一条命中规则的角色；未命中返回空列表 */
    public List<String> resolveRoles(List<String> claimValues) {
        SsoProperties.RoleMapping rm = ssoConfigService.getSsoProperties().getRoleMapping();
        if (rm == null || rm.getRules() == null || claimValues == null || claimValues.isEmpty()) {
            return List.of();
        }
        for (SsoProperties.Rule rule : rm.getRules()) {
            if (rule.getValue() != null && !rule.getValue().isBlank()
                    && rule.getRoles() != null && !rule.getRoles().isEmpty()
                    && claimValues.contains(rule.getValue())) {
                return rule.getRoles();
            }
        }
        return List.of();
    }

    /** 默认角色（规则未配置时的兜底） */
    public String defaultRole() {
        SsoProperties.RoleMapping rm = ssoConfigService.getSsoProperties().getRoleMapping();
        return rm != null && rm.getDefaultRole() != null ? rm.getDefaultRole() : "DATA_ANALYST";
    }
}
