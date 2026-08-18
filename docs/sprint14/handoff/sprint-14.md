# Sprint 14 Handoff：SSO + 认证安全（PRD 完成，待技术方案）

> 更新：2026-08-18（PRD 会话收尾）
> 对应文档：`../DataNest-Sprint14-PRD.md`（v1.0）
> 背景：Sprint 14 原为「多租户」，2026-08-18 用户拍板移除多租户（P2-D16），SSO 提前为 Sprint 14。

---

## 1. 状态看板

| 交付物 | 状态 | 说明 |
|--------|------|------|
| PRD | [OK] v1.0 | F1~F6 范围（OIDC/LDAP/角色映射/登录模式/密码策略/账号绑定），决策 D1~D8 全部定稿 |
| 技术文档 | [OK] v1.0 | `DataNest-Sprint14-技术文档.md`：OIDC 授权码流程（sa-token-oauth2-client）、LDAP 认证/同步（spring-ldap）、角色映射（Nacos 规则）、登录模式（admin 保底）、密码策略（sys_user 五扩展字段 + Nacos 策略）、网关放行、前端改造、审计埋点、测试要点 |
| 测试用例清单 | [待做] | SSO-01~05 逐条 AC + 登录链路 E2E |
| 后端开发 | [待做] | |
| 前端开发 | [待做] | |
| 部署 | [待做] | |

## 2. 已定稿决策（用户 2026-08-18 确认）

| # | 决策 | 结论 |
|---|------|------|
| D1 | 会话机制 | **SSO 回调后建本地 Sa-Token 会话**（复用现有 token 体系） |
| D2 | 首次登录 | 按 email/username **自动绑定已有账号**（同名时）；无则自动建号 |
| D3 | 默认角色 | **可配置**（Nacos），默认数据分析师 |
| D4 | 角色分配 | **claim 映射为主 + 平台手动为辅** |
| D5 | 登录共存 | 混合 + 可切「仅 SSO」，**admin 本地登录保底**（逃生通道） |
| D6 | LDAP 范围 | **完整做**（绑定/同步/映射/认证） |
| D7 | 密码策略 | 复杂度 + 过期 + 失败锁定，**仅本地用户生效** |
| D8 | SSO 技术栈 | 不引入 Spring Security，复用 Sa-Token（OIDC 自研客户端 + spring-ldap） |

## 3. 关键约束（写技术文档/开发时遵守）

- **不引入 Spring Security**：OIDC 自研客户端（sa-token 体系）+ spring-ldap/JNDI。
- `sys_user` 新增字段：`auth_source`（LOCAL/OIDC/LDAP）、`sso_subject`、`password_expire_at`、`login_fail_count`、`locked_until`；存量回填 `LOCAL`。
- SSO 配置/角色映射/登录模式/密码策略全部存 **Nacos**（system 服务配置）。
- 网关需放行 SSO 回调端点；回调带 state 防 CSRF。
- 密码策略仅本地用户生效；SSO/LDAP 用户不受影响。
- 审计：SSO 登录、LDAP 同步、解绑、策略修改均留痕。

## 4. Next Action

1. **提交 PRD + handoff** 到 `feature/phase2-s14-sso` 子分支。
2. **技术方案规划**（writing-plans）：OIDC 授权码流程、LDAP 同步、sys_user 迁移脚本、Nacos 配置项设计、密码策略实现、登录页改造。
3. **测试用例清单**：SSO-01~05 逐条 AC + E2E。
