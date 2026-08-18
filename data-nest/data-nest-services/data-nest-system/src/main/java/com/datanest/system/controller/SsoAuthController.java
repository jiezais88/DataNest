package com.datanest.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.datanest.common.audit.AuditLog;
import com.datanest.common.audit.AuditOpType;
import com.datanest.common.audit.AuditResourceType;
import com.datanest.common.auth.PermissionCode;
import com.datanest.common.model.Result;
import com.datanest.system.config.SsoProperties;
import com.datanest.system.dto.LdapLoginRequest;
import com.datanest.system.dto.SsoConfigVO;
import com.datanest.system.dto.SsoStatusVO;
import com.datanest.system.service.OidcClientService;
import com.datanest.system.service.SsoAuthService;
import com.datanest.system.service.SsoConfigService;
import com.datanest.system.service.SsoSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/**
 * 企业身份登录 / SSO 配置（Sprint 14）。
 * <p>
 * 公开端点：status（登录页状态）、oidc/authorize + oidc/callback（授权码流程）、ldap/login（AD 域登录）。
 * 回调成功 302 到前端登录页，token 走 URL fragment（#ssoToken=xxx，不落服务端日志）。
 * 超管端点：config 读改（auth:config）、ldap/sync（auth:sync）。
 */
@Tag(name = "企业身份登录", description = "Sprint 14 SSO：OIDC/LDAP 登录、身份认证配置、LDAP 用户同步")
@RestController
@RequestMapping("/auth/sso")
public class SsoAuthController {

    private final SsoConfigService ssoConfigService;
    private final SsoAuthService ssoAuthService;
    private final OidcClientService oidcClientService;
    private final SsoSyncService ssoSyncService;

    public SsoAuthController(SsoConfigService ssoConfigService,
                             SsoAuthService ssoAuthService,
                             OidcClientService oidcClientService,
                             SsoSyncService ssoSyncService) {
        this.ssoConfigService = ssoConfigService;
        this.ssoAuthService = ssoAuthService;
        this.oidcClientService = oidcClientService;
        this.ssoSyncService = ssoSyncService;
    }

    @Operation(summary = "SSO 登录页状态（公开）", description = "登录页据此决定是否展示企业身份入口与本地表单")
    @GetMapping("/status")
    public Result<SsoStatusVO> status() {
        SsoProperties props = ssoConfigService.getSsoProperties();
        return Result.ok(new SsoStatusVO(
                props.isEnabled(), props.getMode(),
                props.getOidc() != null && props.getOidc().isEnabled(),
                props.getLdap() != null && props.getLdap().isEnabled()));
    }

    @Operation(summary = "发起 OIDC 授权（公开）", description = "302 重定向到 IdP 授权页")
    @GetMapping("/oidc/authorize")
    public void authorize(HttpServletResponse response) throws IOException {
        response.sendRedirect(oidcClientService.buildAuthorizationUrl());
    }

    @Operation(summary = "OIDC 回调（公开）", description = "换取并校验 id_token 后登录，302 到前端登录页（token 走 URL fragment）")
    @GetMapping("/oidc/callback")
    public void callback(@RequestParam String code,
                         @RequestParam(required = false) String state,
                         HttpServletResponse response) throws IOException {
        Map<String, Object> result = ssoAuthService.loginByOidc(code, state);
        String token = String.valueOf(result.get("token"));
        String frontend = ssoConfigService.getSsoProperties().getFrontendUrl();
        if (frontend == null || frontend.isBlank()) {
            frontend = "http://localhost:3000";
        }
        // fragment 传 token：只到浏览器端，不经过服务端日志/Referer
        response.sendRedirect(frontend.replaceAll("/+$", "") + "/login#ssoToken=" + token);
    }

    @Operation(summary = "LDAP 域账号登录（公开）", description = "AD 域账号密码登录（与本地登录返回结构一致）")
    @PostMapping("/ldap/login")
    public Result<Map<String, Object>> ldapLogin(@Valid @RequestBody LdapLoginRequest req) {
        return Result.ok(ssoAuthService.loginByLdap(req.username(), req.password()));
    }

    @Operation(summary = "读取身份认证配置（仅超管）")
    @SaCheckPermission(PermissionCode.AUTH_CONFIG)
    @GetMapping("/config")
    public Result<SsoConfigVO> config() {
        return Result.ok(ssoConfigService.readConfig());
    }

    @Operation(summary = "保存身份认证配置（仅超管）", description = "写入 Nacos 并热生效（SSO/LDAP/角色映射/密码策略）")
    @SaCheckPermission(PermissionCode.AUTH_CONFIG)
    @AuditLog(resourceType = AuditResourceType.SSO_CONFIG, opType = AuditOpType.UPDATE,
            resourceName = "'身份认证配置'", content = "#vo.mode()")
    @PutMapping("/config")
    public Result<Void> saveConfig(@Valid @RequestBody SsoConfigVO vo) {
        ssoConfigService.saveConfig(vo);
        return Result.ok(null);
    }

    @Operation(summary = "LDAP 用户同步（仅超管）", description = "拉取目录全部用户，自动建号/自动绑定 + 角色映射")
    @SaCheckPermission(PermissionCode.AUTH_SYNC)
    @AuditLog(resourceType = AuditResourceType.SSO_CONFIG, opType = AuditOpType.LDAP_SYNC,
            resourceName = "'LDAP 用户同步'")
    @PostMapping("/ldap/sync")
    public Result<SsoSyncService.SyncResult> sync() {
        return Result.ok(ssoSyncService.syncUsers());
    }
}
