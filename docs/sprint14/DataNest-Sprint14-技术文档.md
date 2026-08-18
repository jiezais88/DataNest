# Sprint 14：SSO + 认证安全 技术文档

> **文档状态**：v1.0（细化版，承接二期总技术文档 §3.1 ADR-012） | **作者**：软件架构师 | **关联文档**：`../DataNest-二期总技术文档.md`（v1.5）、`./DataNest-Sprint14-PRD.md`（v1.0）
>
> **本文档范围**：Sprint 14 SSO 与认证安全的全部技术设计——OIDC/OAuth2 登录桥接、LDAP/AD 接入、角色映射、登录模式、密码策略、sys_user 扩展、迁移脚本、前端改造、测试要点。

---

## 1. 设计总览

```
┌─────────────────────────────────────────────────────────────────────┐
│                    SSO + 认证安全（Sprint 14）                       │
├─────────────────────────────────────────────────────────────────────┤
│ OIDC/OAuth2     授权码流程 → 本地 Sa-Token 会话（ADR-012 落地）      │
│                   sa-token-oauth2-client（OIDC 客户端）              │
│ LDAP/AD         绑定认证 + OU 用户同步 + 角色映射（spring-ldap）      │
│ 角色映射         IdP group/claim → 平台角色（Nacos 规则，映射为主）   │
│ 登录模式         混合 / 仅 SSO（admin 本地登录保底）                 │
│ 密码策略         复杂度 + 过期 + 失败锁定（仅 LOCAL 用户）            │
│ sys_user 扩展    auth_source / sso_subject / password_expire_at /    │
│                   login_fail_count / locked_until                    │
│ 配置              全部走 Nacos（OIDC/LDAP/映射/模式/策略）            │
└─────────────────────────────────────────────────────────────────────┘
```

## 2. 依赖引入

| 依赖 | 用途 | 位置 |
|------|------|------|
| `sa-token-oauth2-client` | OIDC/OAuth2 授权码客户端（与 Sa-Token 同生态，Sa-Token 官方支持 OIDC Discovery） | system 服务 pom（新增） |
| `spring-boot-starter-data-ldap` | LDAP/AD 绑定认证 + 用户同步 | system 服务 pom（新增） |
| `spring-security-crypto` | BCrypt（已有，密码策略校验复用） | system 服务 pom（已存在） |

> **依赖版本**：sa-token-oauth2-client 版本与现有 sa-token 一致（由根 pom 统一管理，子模块禁止写第三方字面量版本）。spring-boot-starter-data-ldap 由 Boot 4 BOM 管理版本。

## 3. OIDC/OAuth2 登录桥接（F1）

### 3.1 授权码流程（ADR-012 落地）

```
浏览器                                   system-service                    IdP
  │  1. GET /api/system/auth/sso/oidc/authorize   │                        │
  │──────────────────────────────────────────────>│                        │
  │                                               │ 2. 302 跳转 IdP 授权页   │
  │<──────────────────────────────────────────────│───────────────────────>│
  │  3. 用户在 IdP 登录授权                        │                        │
  │<────────────────────────────────────────────────────────────────────────│
  │  4. 302 回调 /api/system/auth/sso/oidc/callback?code=xxx&state=yyy      │
  │──────────────────────────────────────────────>│                        │
  │                                               │ 5. 校验 state（防 CSRF）│
  │                                               │ 6. 换 token + 拉 id_token│
  │                                               │ 7. 验签 id_token + subject│
  │                                               │ 8. 匹配/绑定/自动建号    │
  │                                               │ 9. 建本地 Sa-Token 会话  │
  │  10. 302 → 前端（带 Sa-Token 会话 cookie/header）                       │
  │<──────────────────────────────────────────────│                        │
```

### 3.2 关键实现

- **端点**（网关白名单放行，`/api/system/auth/sso/**`）：
  - `GET /api/system/auth/sso/oidc/authorize` — 发起授权（生成 state 存会话 → 302 IdP）
  - `GET /api/system/auth/sso/oidc/callback` — 回调（校验 state → 换 token → 建会话 → 302 前端）
- **OIDC 客户端**：`sa-token-oauth2-client` 自动发现（issuer discovery），配置 `issuer`/`client-id`/`client-secret`/`scope`/`redirect-uri`。
- **id_token 验签**：从 IdP JWKS 拉公钥验签 + 校验 `aud`/`iss`/`exp`。
- **subject 匹配**（PRD D2）：
  1. `sso_subject = id_token.sub` 命中 → 直接登录；
  2. 未命中但 `email`/`username` claim 命中本地 `sys_user` → **自动绑定**（更新 auth_source=OIDC、sso_subject）→ 登录；
  3. 都未命中 → **自动建号**（默认角色见 §5）→ 登录。
- **登录后**：走既有 `AuthService.login` 逻辑写 Sa-Token 会话（roles/permissions 快照 + userInfo），前端登录流程完全复用。

## 4. LDAP/AD 接入（F2）

### 4.1 认证

- 端点：`POST /api/system/auth/sso/ldap/login`（body: `username`/`password`）。
- 流程：`LdapTemplate` 或 `LdapContextSource` bind（`{baseDN},{username}` 绑定 DN）→ 校验通过 → 匹配 `sAMAccountName/uid` → 同 OIDC 的 匹配/绑定/自动建号 → 建本地 Sa-Token 会话。

### 4.2 用户同步

- 端点：`POST /api/system/auth/sso/ldap/sync`（超管，auth:sync）。
- 从配置 OU（`user-filter`）拉取用户：`(sAMAccountName/uid/email/displayName)` → 逐条 upsert `sys_user`（按 `sso_subject` 或 email 匹配）→ 关联角色映射。
- **同步不删除平台账号**（下线用禁用，PRD R3）；同步日志落审计。

## 5. 角色映射（F3）

### 5.1 规则与默认角色

- 规则存 Nacos（`datanest.sso.role-mapping`）：`[{"claim":"group","value":"datanest-engineers","roles":["DATA_ENGINEER"]}, ...]` + `default-role: DATA_ANALYST`。
- 登录时从 id_token/LDAP group claim 提取 → 遍历规则命中 → 赋予角色；**未命中给默认角色**（D3）。
- **映射为主 + 手动为辅（D4）**：登录时若 claim 命中规则则重新映射覆盖；未命中则保留管理员手动调整。

### 5.2 会话写入

- 复用 Sprint 11 `SessionPermissionRefresher`：登录后按用户角色/权限点刷新 Sa-Token 会话 roles/permissions 快照（`StpInterfaceImpl` 读取）。
- SSO/LDAP 用户首次自动建号后即按映射角色刷新会话。

## 6. 登录模式（F4）

- 配置存 Nacos：`datanest.sso.mode` = `mixed`（默认）| `sso-only`。
- `mixed`：登录页显示本地表单 + SSO/LDAP 按钮。
- `sso-only`：登录页隐藏本地表单；**admin 例外**——`admin` 账号本地登录始终允许（逃生通道，PRD D5）。
- 后端登录接口校验：`sso-only` 模式下非 admin 的本地密码登录被拒（返回「仅支持企业身份登录」）。

## 7. 密码策略（F5）

### 7.1 sys_user 扩展字段（Flyway V1.4.0，system 库）

| 列 | 类型 | 说明 |
|----|------|------|
| auth_source | varchar(16) NOT NULL DEFAULT 'LOCAL' | LOCAL/OIDC/LDAP |
| sso_subject | varchar(128) | IdP 唯一标识（OIDC sub / LDAP dn），唯一索引（NULL 不冲突） |
| password_expire_at | timestamp | 密码过期时间（仅 LOCAL） |
| login_fail_count | int NOT NULL DEFAULT 0 | 连续失败次数（仅 LOCAL） |
| locked_until | timestamp | 锁定截止时间（仅 LOCAL） |

### 7.2 策略配置（Nacos `datanest.sso.password-policy`）

```yaml
datanest:
  sso:
    password-policy:
      min-length: 8            # 最小长度
      require-uppercase: true  # 需大写
      require-lowercase: true  # 需小写
      require-digit: true      # 需数字
      require-special: true    # 需特殊字符
      expire-days: 90          # 过期天数（0=不过期）
      warn-before-days: 7      # 到期前提醒
      fail-max: 5              # 连续失败阈值
      lock-minutes: 30         # 锁定分钟数
```

### 7.3 实现

- **复杂度**：`PasswordPolicyValidator`（common 或 system 内，创建/改密/重置时校验，仅 LOCAL）。
- **过期**：登录时检查 `password_expire_at < now` → 强制改密（返回特殊错误码，前端跳强制改密页）；改密后重置 expire。
- **失败锁定**：登录失败 `login_fail_count+1`，达阈值 `locked_until = now + lock-minutes`；登录时检查 locked_until；成功后清零。**仅 LOCAL 用户**（SSO/LDAP 不受影响）。
- 管理员可重置锁定（用户管理 → 重置）。

## 8. Nacos 配置项汇总（system 服务 shared-config）

```yaml
datanest:
  sso:
    enabled: false             # SSO 总开关
    mode: mixed                # mixed / sso-only
    oidc:
      issuer: https://idp.example.com
      client-id: datanest
      client-secret: xxx
      scope: openid,profile,email
      redirect-uri: http://localhost:8080/api/system/auth/sso/oidc/callback
    ldap:
      url: ldap://ldap.example.com:389
      base-dn: dc=example,dc=com
      bind-dn: cn=admin,dc=example,dc=com
      bind-password: xxx
      user-filter: (&(objectClass=user)(sAMAccountName={0}))
      user-search-base: ou=users
    role-mapping:
      default-role: DATA_ANALYST
      rules:
        - {claim: group, value: datanest-engineers, roles: [DATA_ENGINEER]}
        - {claim: group, value: datanest-admins, roles: [GOVERNANCE_ADMIN]}
    password-policy: { ... }   # 见 §7.2
```

> 写配置走 Nacos 发布 API（直插库不下发）；改配置后需重启 system 服务（SSO 配置非热生效）。

## 9. 网关放行与前端改造

### 9.1 网关白名单

- `JwtAuthFilter` 白名单新增：`/api/system/auth/sso/**`（authorize/callback/ldap/login）。
- SSO 回调端点不带 Sa-Token token，网关须放行。

### 9.2 前端改造

| 页面 | 改动 |
|------|------|
| 登录页 | 本地表单 + 「企业 SSO 登录」/「AD 域登录」按钮；`sso-only` 模式隐藏本地表单（按配置加载）；SSO 按钮 302 跳转 authorize 端点 |
| 身份认证（新） | 超管专属：OIDC 配置 / LDAP 配置 / 角色映射规则表 / 登录模式开关 / 密码策略表单 |
| 用户管理 | 认证来源徽章（本地/OIDC/LDAP）+ 详情内绑定 subject + 解绑按钮 + 重置锁定 |
| 强制改密页（新） | 密码过期后跳转，改密后进入系统 |

- 前端 API：`src/api/auth.ts` 增加 SSO 相关端点；`src/types/user.ts` 增加 `authSource`/`ssoSubject`/`lockedUntil`。
- 登录模式/密码策略配置由 `getMe` 或专门配置接口下发给登录页。

## 10. 审计埋点

- SSO 登录成功/失败（含 subject 命中/绑定/建号区分）。
- LDAP 用户同步（新增/更新/跳过数量）。
- 解绑 IdP subject。
- 密码策略配置修改。
- 登录锁定触发/重置。

## 11. 测试要点

### 11.1 单元/集成

- OIDC：state 校验、id_token 验签（mock JWKS）、subject 匹配三分支（命中/绑定/建号）。
- LDAP：bind 认证 mock、同步 upsert 逻辑、不删除账号。
- 密码策略：复杂度校验矩阵、过期强制改密、锁定/解锁。

### 11.2 E2E

| 场景 | 用例 |
|------|------|
| OIDC 登录 | mock IdP 授权码回调 → 登录成功 → 会话可用 |
| 自动绑定 | IdP email=本地已有账号 → 绑定 → 登录成功 |
| 自动建号 | IdP email 不存在 → 建号 + 默认角色 → 登录 |
| 仅 SSO 模式 | 本地表单隐藏；admin 仍可本地登录 |
| 角色映射 | claim 命中 → 角色正确；未命中 → 默认角色 |
| 密码策略 | 弱密码拒绝；过期强制改密；连续失败锁定 |
| 解绑 | 解绑后 SSO 不再命中该账号 |

### 11.3 存量兼容

- 存量用户 auth_source 回填 LOCAL，行为不变（登录回归全绿）。

## 12. 风险与对策

| # | 风险 | 对策 |
|---|------|------|
| R1 | SSO 配置错误锁死平台 | admin 本地登录保底 + 配置项先行校验 |
| R2 | 自动建号误绑他人账号 | 同名绑定需 email+subject 双校验；解绑可回退 |
| R3 | LDAP 同步误删账号 | 同步只增/更新不删除；下线用禁用 |
| R4 | claim 角色覆盖手动调整 | 未重新命中规则前保留手动角色 |
| R5 | 锁定误伤正常用户 | 阈值可配置 + 管理员重置 + 登录页明确提示 |

## 13. 修订记录

| 版本 | 日期 | 修订内容 | 作者 |
|------|------|----------|------|
| v1.0 | 2026-08-18 | 初始版本：OIDC 授权码流程、LDAP 认证/同步、角色映射、登录模式、密码策略、sys_user 扩展字段、Nacos 配置项、网关放行、前端改造、测试要点 | 软件架构师 |
