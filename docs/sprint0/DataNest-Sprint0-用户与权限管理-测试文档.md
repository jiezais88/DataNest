# DataNest Sprint0 用户与权限管理测试文档

| 项目        | 内容                                     |
|-------------|------------------------------------------|
| 文档编号    | DataNest-Sprint0-用户与权限管理-测试文档 |
| 版本        | v1.0                                     |
| 所属 Sprint | Sprint0                                  |
| 更新日期    | 2026-07-24                               |
| 状态        | 已完成                                   |

---

## 1. 概述

本文档记录 Sprint0 阶段「用户与权限管理」模块的测试方案、测试用例、执行结果以及关键缺陷修复。Sprint0 的核心目标是打通「admin
创建用户 → 角色分配 → 重置密码 → 禁用用户 → 登录校验」完整链路，确保后端 API、前端页面与 E2E 自动化测试全部通过。

测试策略采用 **先 API 后 E2E**：先用轻量级 HTTP 脚本验证后端接口正确性，排除后端问题后再用 Playwright 跑通完整前端流程。

---

## 2. 测试范围

| 模块     | 测试内容                                                |
|----------|---------------------------------------------------------|
| 认证登录 | admin / 普通用户 / 禁用用户登录；错误提示展示           |
| 用户管理 | 创建用户、查询用户列表、重置密码、禁用/启用用户         |
| 权限控制 | admin 可见「系统管理」菜单；数据工程师不可见            |
| 数据精度 | Java `Long` 雪花 ID 在前后端传递时不丢失精度            |
| 部署集成 | Docker Compose 全栈启动；Nginx 反向代理与 upstream 解析 |

---

## 3. 测试环境

### 3.1 服务清单

基于 `data-nest/docker-compose.yml`：

| 服务        | 容器名               | 端口               | 说明                      |
|-------------|----------------------|--------------------|---------------------------|
| nacos-mysql | datanest-nacos-mysql | 3306               | Nacos 持久化数据库        |
| nacos       | datanest-nacos       | 8848 / 9848 / 8081 | 注册与配置中心            |
| postgres    | datanest-postgres    | 5432               | 用户权限元数据库          |
| redis       | datanest-redis       | 6379               | Sa-Token 会话存储         |
| gateway     | datanest-gateway     | 8080               | Spring Cloud Gateway      |
| system      | datanest-system      | 8087               | 用户与权限服务            |
| frontend    | datanest-frontend    | 3000               | Nginx 静态资源 + API 代理 |

### 3.2 启动命令

```bash
cd data-nest
docker compose up -d
```

### 3.3 测试账号

| 角色       | 用户名 | 密码                              |
|------------|--------|-----------------------------------|
| 系统管理员 | admin  | admin123                          |
| 数据工程师 | zhangw | Zhangw@123 → Zhangw@456（重置后） |

---

## 4. API 测试

### 4.1 测试脚本

脚本位于 `data-nest/tmp/e2e/api-tests/`：

- `api-smoke-test.js`：登录 + 创建用户 + 查询列表冒烟
- `api-reset-password-test.js`：重置密码后用新密码登录
- `api-disabled-login-test.js`：禁用用户后登录失败

### 4.2 关键接口

| 接口     | 方法 | 路径                                        | 说明             |
|----------|------|---------------------------------------------|------------------|
| 登录     | POST | `/api/system/auth/login`                    | 返回 JWT Token   |
| 创建用户 | POST | `/api/system/users`                         | 需要 admin Token |
| 查询用户 | GET  | `/api/system/users?page=1&pageSize=20`      | 需要 admin Token |
| 重置密码 | PUT  | `/api/system/users/{userId}/reset-password` | 需要 admin Token |
| 切换状态 | PUT  | `/api/system/users/{userId}/toggle`         | 需要 admin Token |

### 4.3 执行方式

```bash
cd data-nest/tmp/e2e/api-tests
node api-smoke-test.js
node api-reset-password-test.js
node api-disabled-login-test.js
```

可通过环境变量覆盖基地址：

```bash
BASE_URL=http://localhost:8080 node api-smoke-test.js
```

### 4.4 执行结果

| 脚本                       | 结果 |
|----------------------------|------|
| api-smoke-test.js          | 通过 |
| api-reset-password-test.js | 通过 |
| api-disabled-login-test.js | 通过 |

---

## 5. E2E 测试

### 5.1 测试脚本

- 文件：`data-nest/tmp/e2e/sprint0.spec.js`
- 框架：Playwright
- 基地址：`http://localhost:3000`（frontend）

### 5.2 测试步骤

1. admin 登录，验证「系统管理」「用户管理」菜单可见
2. 进入用户管理列表
3. 若已存在 `zhangw`，先禁用清理，保证幂等
4. 创建数据工程师 `zhangw`
5. `zhangw` 登录，验证无「系统管理」菜单，有「数据工程」菜单
6. admin 重置 `zhangw` 密码
7. `zhangw` 使用新密码登录成功
8. admin 禁用 `zhangw`
9. `zhangw` 登录，页面提示「账号已禁用」

### 5.3 设计要点

- **不依赖 toast 断言**：列表状态变化是最终可靠断言点
- **幂等清理**：测试开头检查并清理已有 `zhangw`，避免脏数据导致失败
- **原生 prompt 处理**：重置密码使用 `page.once('dialog', ...)` 自动填入新密码

### 5.4 执行方式

```bash
cd data-nest/tmp/e2e
npx playwright test sprint0.spec.js --headed
```

### 5.5 执行结果

| 用例                           | 结果 |
|--------------------------------|------|
| Sprint0 用户与权限管理完整流程 | 通过 |

---

## 6. 关键缺陷与修复

### 6.1 Long 类型 ID 精度丢失

#### 现象

重置密码、禁用用户等操作传入的 `userId` 与数据库实际 ID 不一致，导致操作失败或影响错用户。

#### 根因

后端使用雪花算法生成 `Long` 类型 ID，JavaScript `Number` 类型无法精确表示超过 `2^53` 的大整数，前端接收到的 ID 最后一位发生变化。

#### 修复方案

全局统一将 `Long`/`long` 序列化为字符串，由 Jackson 3 处理，不修改 DTO 字段类型。

配置下沉到 `data-nest-common`，通过 Spring Boot 自动配置向 gateway、system 等所有服务自动生效：

`data-nest-common/src/main/java/com/datanest/common/jackson/JacksonConfig.java`：

```java

@AutoConfiguration
public class JacksonConfig {

    @Bean
    public SimpleModule longToStringModule() {
        SimpleModule module = new SimpleModule("long-to-string");
        module.addSerializer(Long.class, new LongToStringSerializer());
        module.addSerializer(Long.TYPE, new LongToStringSerializer());
        return module;
    }

    public static class LongToStringSerializer extends ValueSerializer<Long> {

        @Override
        public void serialize(Long value, JsonGenerator gen, SerializationContext ctxt)
                throws JacksonException {
            if (value == null) {
                gen.writeNull();
            } else {
                gen.writeString(value.toString());
            }
        }
    }
}
```

前端同步将 `userId`/`id` 类型改为 `string`：

- `data-nest-frontend/src/api/auth.ts`
- `data-nest-frontend/src/store/useAuthStore.ts`

### 6.2 禁用用户登录无错误提示

#### 现象

被禁用用户登录时页面空白，未展示后端返回的「账号已禁用」提示。

#### 根因

登录页面仅处理 `code === 200` 的成功分支，未处理失败分支。

#### 修复

`data-nest-frontend/src/pages/login/index.tsx`：

```tsx
const result = await login({username, password, rememberMe});
if (result.code === 200) {
    setAuth(result.data.token, result.data.userInfo);
    navigate('/');
} else {
    setError(errorMessages[result.code] || result.message || '登录失败');
}
```

### 6.3 Nginx 动态 upstream 解析失败

#### 现象

frontend 容器启动后无法访问 gateway，导致 API 请求失败。

#### 修复

`data-nest-frontend/nginx.conf` 中使用变量形式的 proxy_pass，强制 Nginx 在请求时解析 `gateway` 主机名，而不是启动时缓存
IP。

---

## 7. 测试结论

| 检查项               | 状态   |
|----------------------|--------|
| API 冒烟测试         | 通过   |
| 重置密码 API 链路    | 通过   |
| 禁用用户 API 链路    | 通过   |
| Sprint0 E2E 完整流程 | 通过   |
| Long 精度问题        | 已修复 |
| 登录错误提示         | 已修复 |
| 部署集成             | 已修复 |

**结论**：Sprint0 用户与权限管理模块功能、API、E2E 均达到可交付状态。

---

## 8. 测试产物清单

| 产物         | 路径                                                     | 说明                |
|--------------|----------------------------------------------------------|---------------------|
| API 冒烟脚本 | `data-nest/tmp/e2e/api-tests/api-smoke-test.js`          | 登录、创建、查询    |
| 重置密码脚本 | `data-nest/tmp/e2e/api-tests/api-reset-password-test.js` | 重置后登录验证      |
| 禁用登录脚本 | `data-nest/tmp/e2e/api-tests/api-disabled-login-test.js` | 禁用后登录失败验证  |
| E2E 完整流程 | `data-nest/tmp/e2e/sprint0.spec.js`                      | Playwright 完整用例 |
| 测试截图     | `data-nest/tmp/e2e/test-results/`                        | E2E 执行截图        |
| API 日志     | `data-nest/tmp/logs/`                                    | 服务运行日志        |

> `tmp/` 目录已加入根目录 `.gitignore`，测试产物不会被提交。

---

## 9. 后续建议

1. **用例沉淀**：将 `tmp/e2e/` 下的脚本迁移到正式测试目录（如 `data-nest-frontend/e2e/` 或
   `data-nest-system/src/test/e2e/`），并接入 CI。
2. **数据工厂**：为 E2E 引入独立测试数据库或数据工厂，避免与开发环境共用 `zhangw` 等账号。
3. **角色矩阵扩展**：Sprint1 增加「数据分析师」「访客」等角色的权限断言。
4. **并发与性能**：补充用户列表分页、并发创建用户等性能测试。
