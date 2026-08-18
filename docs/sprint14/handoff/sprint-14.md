# Sprint 14 Handoff：SSO + 认证安全

> 更新：2026-08-18（开发会话收尾）
> 对应文档：`../DataNest-Sprint14-PRD.md`（v1.0）、`../DataNest-Sprint14-技术文档.md`（v1.1）
> 背景：Sprint 14 原为「多租户」，2026-08-18 用户拍板移除多租户（P2-D16），SSO 提前为 Sprint 14。

---

## 1. 状态看板

| 交付物 | 状态 | 说明 |
|--------|------|------|
| PRD | [OK] v1.0 | F1~F6 范围（OIDC/LDAP/角色映射/登录模式/密码策略/账号绑定），决策 D1~D8 全部定稿 |
| 技术文档 | [OK] v1.1 | 已同步实现：自研 OIDC 客户端（nimbus 验签，D8）、LDAP 编程式 JNDI、配置热生效、回调 hash token、memberOf operational、nacos-init 重置注意事项、Flyway V1.2.0 |
| 后端开发 | [OK] | system + common + gateway，全部自测通过（见 §5 验证记录） |
| 前端开发 | [OK] | 登录页/身份认证页/用户管理/强制改密页 + 路由守卫，构建部署完成 |
| 测试用例清单 | [OK] | 2026-08-18 测试收尾：**26/26 全绿**（API 17 + E2E 9），详见 `../测试报告.md`；含 1 个真实 Bug 修复（`unbindSso` MyBatis-Plus NOT_NULL 坑） |

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
| D8 | SSO 技术栈 | 不引入 Spring Security，复用 Sa-Token（OIDC 自研客户端 + JNDI LDAP） |

## 3. 实现清单（本轮完成）

### 后端
- **common**：ErrorCode 新增 1007~1021（密码策略/锁定/过期/SSO/LDAP）；PermissionCode 新增 `auth:config`/`auth:sync`；AuditOpType 新增 SSO_LOGIN/LDAP_SYNC/UNBIND/UNLOCK；AuditResourceType 新增 SSO_CONFIG。
- **Flyway V1.2.0**（system 库）：`sys_user` 加 `auth_source`/`sso_subject`/`password_expire_at`/`login_fail_count`/`locked_until`；sso_subject 部分唯一索引；权限点种子 188/189。
- **SsoConfigService**：Nacos `sso-config.yaml` 读写 + 监听热生效（保存后 publishConfig 免重启）。
- **OidcClientService**：自研授权码客户端（nimbus 验签 RS256 + aud/iss/exp 校验 + state 防 CSRF + Discovery 兜底），用户名优先 `preferred_username`。
- **LdapClientService**：编程式 JNDI（provider URL 拼 base-dn；memberOf 显式请求 operational 属性；属性名大小写不敏感）。
- **UserService**：本地登录升级（来源校验/失败锁定/密码过期 mustChangePwd）；SSO 三分支（subject 命中/email 绑定/自动建号）+ D4 角色映射；解绑/解锁。
- **PasswordPolicyService**：复杂度/锁定/过期（`LambdaUpdateWrapper` 显式 set null 清锁）。
- **AuthSessionService**：登录会话写入抽取（本地/OIDC/LDAP 共用）。
- **SsoAuthController**：status（公开）/oidc authorize+callback（公开，hash token 回传）/ldap login（公开）/config 读改/auth:config/ldap sync（auth:sync）。
- **网关**：白名单放行 status/oidc authorize/callback/ldap login。

### 前端
- `api/auth.ts`：SSO 相关 API + UserVO 扩展；`store/useAuthStore.ts`：mustChangePwd。
- 登录页：SSO/AD 入口 + sso-only 折叠管理员登录 + `#ssoToken=` 回调处理 + 强制改密跳转。
- `pages/system/auth-config`（新）：登录策略/OIDC/LDAP/角色映射/密码策略表单 + LDAP 同步。
- `pages/force-change-password`（新）：过期强制改密。
- 用户管理：认证来源徽章 + 锁定状态 + 解绑/解锁/重置（仅 LOCAL）。
- 路由：`/system/auth-config`、`/force-change-password`；ProtectedRoute 增加 mustChangePwd 守卫。
- Sidebar：系统管理新增「身份认证」（超管）。

## 4. 测试环境（容器化，非生产）

> **一条命令拉起**：`docker compose --profile test up -d middleware-test-idp middleware-test-openldap`
> OpenLDAP 拉起后需执行一次初始化（osixia 镜像不支持启动自动初始化，见下方踩坑）：
> `docker exec datanest-middleware-test-openldap /tmp/init-mock-openldap.sh`

- **Mock OIDC IdP**：`docker/mock-idp.Dockerfile` + compose 服务 `middleware-test-idp`（`--profile test`，端口 9040，issuer=http://host.docker.internal:9040，用户 alice/bob/carol/dave）。
- **测试 OpenLDAP**：compose 服务 `middleware-test-openldap`（osixia/openldap:1.5.0 裸镜像，端口 1389，dn=dc=example,dc=com，admin/admin123；用户 zhangsan/lisi）。
  - 初始化脚本 `scripts/sso-mock-idp/init-mock-openldap.sh`（bind mount 到 /tmp，幂等）。
  - system 容器经 compose 网络用 `ldap://middleware-test-openldap:389` 访问（Nacos sso-config 已配好）。
- **`.dockerignore` 已放行** `scripts/sso-mock-idp/`（原 scripts/ 被排除导致 COPY 失败）。

### OpenLDAP 容器化踩坑（2026-08-18 定稿）
- osixia/openldap 镜像 **自带一个 memberof overlay**（groupOfUniqueNames/uniqueMember），且 OpenLDAP 的 memberof **一经添加不可删除**（unwilling to perform 53）。因此**种子组必须用 `groupOfUniqueNames` + `uniqueMember`** 匹配自带 overlay，不要自定义 overlay。
- 自定义 ldif 挂载到 bootstrap 目录不可行：`chown` 失败 / bind mount 文件无法 mv-rm / config 段需 EXTERNAL 认证（status 50）。
- `/container/startup/` 目录该版本不支持；`/container/run/startup.sh` 执行时序不对（slapd 尚未就绪）。
- **最终方案**：osixia 裸镜像 + 手动执行一次 init 脚本（幂等）。导入顺序**先用户后组**，memberOf 才会由自带 overlay 回填。

## 5. 验证记录（2026-08-18 全部通过）

| 场景 | 结果 |
|------|------|
| admin 本地登录（复杂度/过期/锁定后正常） | 200 |
| 弱密码创建用户 | 1007 密码不满足复杂度要求 |
| 5 次错误密码 → 锁定 → 正确密码 1008 → 解锁 → 登录成功 | 通过（含 updateById null 坑修复） |
| 密码过期登录 → mustChangePwd=true → 强制改密 → 新密码登录 | 通过 |
| OIDC 全链路（authorize→IdP→callback→/login#ssoToken） | 通过 |
| OIDC 自动建号 dave（preferred_username）+ DATA_ENGINEER 映射 | 通过 |
| OIDC 自动绑定 carol→carol_local（保留手动角色） | 通过 |
| OIDC bob 自动建号 + GOVERNANCE_ADMIN 映射 | 通过 |
| **容器化后回归**：LDAP 登录 zhangsan→DATA_ENGINEER、LDAP 同步 total=2 updated=2、OIDC authorize→callback→/login#ssoToken | 通过 |
| **Jackson 3 替换回归**：OidcClientService 去 fastjson2 → 注入 Boot JsonMapper Bean（`readTree`+`path().asString()`），OIDC 全链路 + LDAP 登录 | 通过 |
| **全项目 fastjson2→JsonUtils 迁移**：31 Java 文件 + 10 pom 全部移除 fastjson2（新增 `com.datanest.common.json.JsonUtils` 静态工具，持单例 Jackson 3 JsonMapper；JSONObject/JSONArray→ObjectNode/ArrayNode）。全量编译通过、8 服务（system/alert/realtime/data-service/engineering/governance/worker/gateway）全部 healthy 启动。冒烟：admin 登录、SSO 配置保存热生效、OIDC 全链路、LDAP 登录 zhangsan→DATA_ENGINEER | 通过 |
| LDAP 域登录 zhangsan（+组映射 DATA_ENGINEER）/ 错误密码 1017 | 通过 |
| LDAP 同步（total=2 created=1 updated=1） | 通过 |
| 解绑 carol_local → 恢复 LOCAL → 本地登录 | 通过 |
| sso-only 模式普通用户 1013 / admin 保底 200 | 通过 |
| LDAP 绑定用户本地登录 | 1014 该账号为企业身份账号 |
| 身份认证配置保存 → Nacos → 热生效 | 通过 |

**已修复的坑**：MyBatis-Plus updateById 忽略 null 字段导致解锁不生效（改 LambdaUpdateWrapper 显式 set null）；LDAP JNDI 相对名需 provider URL 拼 base-dn；memberOf 是 operational 属性需显式请求；审计 SpEL 中文字面量需单引号。

## 6. 已知坑 / 注意事项

- **nacos-init 重置**：`docker compose up`（任意服务）触发 `middleware-nacos-init` 把 `sso-config.yaml` 重置为默认（enabled=false）。身份认证页保存的运行时配置在重启后会丢，需重新保存。产品运行环境（非 compose）不受影响。技术文档已注明。
- **OIDC 浏览器跳转需显式 `authorizationEndpoint`**（2026-08-18 测试发现）：Discovery 拿到的授权端点是 `http://host.docker.internal:9040/authorize`，但宿主机浏览器**无法解析** `host.docker.internal`（Windows Docker Desktop 仅对容器注入此 DNS），E2E SU-02 因此卡死。**修复**：在 `sso-config.yaml` 显式配 `authorizationEndpoint=http://localhost:9040/authorize`（覆盖 Discovery），token/jwks 端点留空走 Discovery（容器内访问 OK）。生产环境用公网 IdP URL 时无需此配置。详见 `测试报告.md` §3.1。
- **MyBatis-Plus NOT_NULL 策略坑（已修）**：`UserService.unbindSso` 用 `updateById(user)` 设置 `ssoSubject=null` 不生效，导致解绑后 `sso_subject` 残留、AC-8「解绑后 SSO 登录不再命中」不成立。**修复**：改用 `LambdaUpdateWrapper` 显式 `set(User::getSsoSubject, null)`（与 `unlockUser`/`resetLoginState` 一致）。已重建 `datanest-app-system`。
- **测试用户**：OIDC/LDAP 验证在 system 库创建了 alice/bob/carol/carol_local/dave/zhangsan/lisi/testlock，清理前先问用户。
- **sa-token 过期配置警告**：shared-security.yaml 的 `activity-timeout` 已过期（应换 `active-timeout`），本轮未动（既有问题，范围外）。

## 7. Next Action

1. **前端浏览器联调确认**：用户在 IDE 预览确认登录页 SSO 入口、身份认证页、用户管理页渲染与交互（当前 SSO 已保持启用，mixed 模式，可直接登录页点企业 SSO / AD 域入口验证）。
2. **测试用例清单**：[OK] 26/26 全绿，详见 `../测试报告.md`。
3. **清理测试产物**：`e2e_s14_*` 已自动清理；`dave` 已恢复开发原始状态；SSO 运行时配置保持启用（便于联调确认）。`test-results/` 残留因 IDE safe-delete 守卫未清，手动 cmd rmdir。
4. **代码提交**：feature/phase2-s14-sso 子分支中文提交（写 UTF-8 文件后 git commit -F）。当前 working tree 含：① `system` 服务 `unbindSso` 修复；② 三个新文件 `e2e/sprint14/api/sso-api.spec.ts`、`e2e/sprint14/e2e/sso.spec.ts`、`e2e/sprint14/e2e/helpers/seed.ts`；③ `docs/sprint14/测试报告.md` + handoff 更新。
