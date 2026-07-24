# DataNest Sprint 0 技术文档

> **Sprint**：Sprint 0 — 项目初始化 + 基础设施 + 用户与权限管理
> **文档状态**：Working Draft | **作者**：软件架构师 | **日期**：2026-07-23
> **关联文档**：`DataNest-产品规格文档-v2.0.md`、`DataNest-技术架构文档-v2.2.md`、`DataNest-Sprint0-用户与权限管理-PRD.md`

---

## 目录

1. [Sprint 概述](#1-sprint-概述)
2. [交付物清单](#2-交付物清单)
3. [项目结构搭建](#3-项目结构搭建)
4. [中间件集成（Docker Compose）](#4-中间件集成docker-compose)
5. [Gateway + 鉴权框架](#5-gateway--鉴权框架)
6. [用户与权限管理（system-service）](#6-用户与权限管理system-service)
7. [数据库设计（Flyway 迁移）](#7-数据库设计flyway-迁移)
8. [共享配置设计](#8-共享配置设计)
9. [公共模块设计](#9-公共模块设计)
10. [前端工程骨架](#10-前端工程骨架)
11. [本地开发环境](#11-本地开发环境)
12. [Sprint 0 ADR](#12-sprint-0-adr)
13. [验收标准](#13-验收标准)
14. [风险与对策](#14-风险与对策)

---

## 1. Sprint 概述

### 1.1 Sprint 目标

搭建 DataNest 的 **最小可用骨架**——含用户体系和 RBAC 权限模型。完成后：

```bash
docker compose up -d                # 一键拉起全部中间件 + 前后端
cd data-nest && mvn clean install   # 全量编译通过
```

用 `admin / admin123` 登录，左侧菜单根据角色动态显示。

### 1.2 Sprint 范围

| # | 工作项                                    | 说明                                                            |
|---|-------------------------------------------|-----------------------------------------------------------------|
| 1 | Maven 父子 POM 脚手架                     | 3 个模块（root + common + server），后续 Sprint 按需加          |
| 2 | Nacos 3.1.1 注册中心 + 配置中心           | 服务发现 + shared-configs                                       |
| 3 | PostgreSQL 16                             | 元数据 + 用户权限存储                                           |
| 4 | Doris FE+BE                               | OLAP 引擎（Sprint 1 消费）                                      |
| 5 | **server 应用**（路由 + 鉴权 + 用户管理） | Sa-Token JWT + 用户/角色 CRUD + Flyway 迁移 + 4 预置角色 + RBAC |
| 6 | shared-configs（4 个 YAML）               | datasource / security / auth / doris                            |
| 7 | 前端骨架（登录页入口 + 用户管理页）       | React + TS + Vite                                               |

### 1.3 不在本 Sprint 范围

| 暂缓项                      | 理由                  | 后续 Sprint |
|-----------------------------|-----------------------|:-----------:|
| Redis                       | Sa-Token 内存模式足够 | Sprint 1-2  |
| OpenSearch / Neo4j          | 搜索/血缘，Sprint 6-8 |  Sprint 6   |
| DolphinScheduler            | 调度引擎，Sprint 3-5  |  Sprint 3   |
| MinIO + Iceberg + Flink CDC | 实时/湖仓，Sprint 8-9 |  Sprint 8   |
| SSO / 第三方登录 / 验证码   | P2 功能               |   企业版    |
| 密码复杂度策略 / 登录锁定   | 安全加固              |  Sprint 3   |
| 数据级权限（表/字段级）     | Sprint 10             |  Sprint 10  |

### 1.4 技术栈

| 组件                         | 版本       | 用途              |
|------------------------------|------------|-------------------|
| JDK                          | 21 LTS     | —                 |
| Spring Boot                  | 4.0.7      | 微服务框架        |
| Spring Cloud                 | 2025.1.2   | 微服务生态        |
| SCA                          | 2025.1.0.0 | Nacos 集成        |
| Nacos                        | 3.1.1      | 注册 + 配置中心   |
| PostgreSQL                   | 16         | 元数据 + 用户权限 |
| Apache Doris                 | 4.1.3      | OLAP 引擎         |
| Maven                        | 3.9+       | 构建              |
| React 18 + TS 5.x + Vite 6.x | 最新稳定版 | 前端              |
| Sa-Token                     | 1.44+      | 鉴权（内存模式）  |
| MyBatis-Plus                 | 3.5.10     | ORM + 雪花主键    |
| Flyway                       | 10.22.0    | 数据库迁移        |
| Nginx                        | alpine     | 前端托管          |

---

## 2. 交付物清单

| #  | 交付物                                                              | 类型 | 验收方式                              |
|----|---------------------------------------------------------------------|------|---------------------------------------|
| D1 | `data-nest/pom.xml` + 3 个子模块                                    | 代码 | `mvn clean install` 成功              |
| D2 | `docker-compose.yml`（中间件 + gateway + system + 前端，共 7 容器） | 配置 | `docker compose up -d` 全容器 healthy |
| D3 | 4 个 `shared-configs/` YAML                                         | 配置 | Nacos Console 可见                    |
| D4 | `data-nest-common/`                                                 | 代码 | 编译通过                              |
| D5 | `data-nest-gateway/`（登录 + JWT + 路由）                           | 代码 | `POST /api/auth/login` 返回 Token     |
| D6 | `data-nest-system/`（用户/角色 CRUD + Flyway 迁移）                 | 代码 | 管理员可创建用户并分配角色            |
| D7 | `data-nest-frontend/`（登录页 + 主页 + 用户管理）                   | 代码 | `npm run dev` 可启动                  |
| D8 | `start-local.sh`                                                    | 脚本 | 一键启动全栈                          |

---

## 3. 项目结构搭建

### 3.1 目录总览

```
data-nest/
├── pom.xml
├── docker-compose.yml
├── .env
├── .gitignore
├── start-local.sh
│
├── data-nest-common/                     # 🔧 公共模块（纯 Java 库）
│   └── src/main/java/com/datanest/common/
│       ├── model/        # TableRef, Result<T>, UserInfo, PageResult
│       ├── event/        # LineageEvent, MetadataChangeEvent
│       ├── exception/    # ErrorCode, BusinessException
│       └── util/         # SqlUtils
│
├── data-nest-gateway/                    # 🚪 API 网关
│   └── src/main/java/com/datanest/gateway/
│       ├── GatewayApplication.java
│       ├── controller/AuthController.java   # 登录入口
│       ├── config/RouteConfig.java
│       └── filter/JwtAuthFilter.java
│
├── data-nest-system/                     # 👤 用户与权限管理
│   └── src/main/java/com/datanest/system/
│       ├── SystemApplication.java
│       ├── controller/   # UserController, RoleController
│       ├── service/      # UserService, RoleService
│       ├── mapper/       # UserMapper, RoleMapper, PermissionMapper
│       ├── entity/       # User, Role, Permission, UserRole
│       ├── dto/          # UserDTO, UserCreateRequest...
│       └── resources/db/migration/  # V1.0.0, V1.0.1
│
├── data-nest-frontend/                   # 🎨 前端工程
│   ├── package.json, vite.config.ts, tsconfig.json
│   ├── Dockerfile, nginx.conf
│   └── src/
│       ├── main.tsx, App.tsx
│       ├── pages/login/          # 登录页
│       ├── pages/system/users/   # 用户管理
│       ├── pages/home/           # 首页骨架
│       ├── store/                # 用户/Token 状态
│       ├── api/                  # axios 封装
│       └── components/           # Layout, Sidebar...
│
└── shared-configs/
    ├── shared-datasource.yaml
    ├── shared-security.yaml
    └── shared-doris.yaml
```

> 📌 **Sprint 0 原则**：只建当前需要的模块。engineering / governance / data-service 三个业务微服务在对应的后续 Sprint
> 中按需添加，不在 Sprint 0 建空壳。Docker Compose 也只起 gateway + system + frontend。

### 3.2 Root POM

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.datanest</groupId>
    <artifactId>data-nest</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <name>DataNest</name>
    <description>开源一站式数据中台</description>

    <properties>
        <java.version>21</java.version>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>

        <spring-boot.version>4.0.7</spring-boot.version>
        <spring-cloud.version>2025.1.2</spring-cloud.version>
        <spring-cloud-alibaba.version>2025.1.0.0</spring-cloud-alibaba.version>

        <sa-token.version>1.44.0</sa-token.version>
        <mybatis-plus.version>3.5.10</mybatis-plus.version>
        <flyway.version>10.22.0</flyway.version>

        <maven-compiler-plugin.version>3.13.0</maven-compiler-plugin.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring-boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-dependencies</artifactId>
                <version>${spring-cloud.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>com.alibaba.cloud</groupId>
                <artifactId>spring-cloud-alibaba-dependencies</artifactId>
                <version>${spring-cloud-alibaba.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>cn.dev33</groupId>
                <artifactId>sa-token-spring-boot4-starter</artifactId>
                <version>${sa-token.version}</version>
            </dependency>
            <dependency>
                <groupId>com.baomidou</groupId>
                <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
                <version>${mybatis-plus.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <modules>
        <module>data-nest-common</module>
        <module>data-nest-gateway</module>
        <module>data-nest-system</module>
        <!-- 后续 Sprint 按需添加:
        <module>data-nest-engineering</module>    Sprint 1: 数据集成
        <module>data-nest-governance</module>     Sprint 1: 元数据+治理
        <module>data-nest-data-service</module>   Sprint 10: SQL 终端+API
        -->
    </modules>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>${maven-compiler-plugin.version}</version>
                    <configuration>
                        <source>21</source><target>21</target>
                        <parameters>true</parameters>
                    </configuration>
                </plugin>
                <plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                    <version>${spring-boot.version}</version>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</project>
```

### 3.3 各服务差异化依赖

| 服务        | 特有依赖                                              | 说明             |
|-------------|-------------------------------------------------------|------------------|
| **common**  | 无 Spring Starter                                     | 纯 Java 库       |
| **gateway** | `spring-cloud-starter-gateway` + `sa-token`           | WebFlux，无 DB   |
| **system**  | `mybatis-plus` + `postgresql` + `flyway` + `sa-token` | 唯一有 DB 的服务 |

后续 Sprint 添加业务服务时，参照 system-service POM 结构扩展。

### 3.4 system-service POM

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" ...>
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.datanest</groupId>
        <artifactId>data-nest</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>data-nest-system</artifactId>

    <dependencies>
        <dependency>
            <groupId>com.datanest</groupId>
            <artifactId>data-nest-common</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-openfeign</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-loadbalancer</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>cn.dev33</groupId>
            <artifactId>sa-token-spring-boot4-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

```yaml
# system-service application.yml
spring:
  application:
    name: system-service
  config:
    import:
      - nacos:system-service.yaml?refreshEnabled=true
      - nacos:shared-datasource.yaml?refreshEnabled=true&group=shared-configs
      - nacos:shared-security.yaml?refreshEnabled=true&group=shared-configs
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_ADDR:localhost:8848}
        namespace: datanest-dev
      config:
        server-addr: ${NACOS_ADDR:localhost:8848}
        namespace: datanest-dev
        file-extension: yaml
  datasource:
    driver-class-name: org.postgresql.Driver
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true

server:
  port: 8087
```

---

## 4. 中间件集成（Docker Compose）

### 4.1 中间件清单

| 中间件         | 版本  | 端口       | 消费方                                            | Sprint |
|----------------|-------|------------|---------------------------------------------------|:------:|
| **Nacos**      | 3.1.1 | 8848       | 所有微服务                                        |   0    |
| **PostgreSQL** | 16    | 5432       | system（用户权限）→ governance（Sprint 1 元数据） |  0/1   |
| **Doris**      | 4.1.3 | 9030(JDBC) | integration（Sprint 1）                           |   1    |

### 4.2 docker-compose.yml

```yaml
version: "3.8"

services:
  # ============ 中间件 ============

  nacos:
    image: nacos/nacos-server:v3.1.1
    container_name: datanest-nacos
    environment:
      MODE: standalone
      PREFER_HOST_MODE: hostname
    ports:
      - "8848:8848"
      - "9848:9848"
    volumes:
      - ./data/nacos:/home/nacos/data
    healthcheck:
      test: [ "CMD", "curl", "-f", "http://localhost:8848/nacos/v1/console/health/readiness" ]
      interval: 10s; timeout: 5s; retries: 15

  postgres:
    image: postgres:16-alpine
    container_name: datanest-postgres
    environment:
      POSTGRES_USER: datanest
      POSTGRES_PASSWORD: ${PG_PASSWORD:-datanest123}
      POSTGRES_DB: datanest
    ports:
      - "5432:5432"
    volumes:
      - ./data/postgres:/var/lib/postgresql/data
    healthcheck:
      test: [ "CMD-SHELL", "pg_isready -U datanest" ]
      interval: 10s; timeout: 5s; retries: 5

  doris-fe:
    image: apache/doris:4.1.3-fe-ubuntu
    container_name: datanest-doris-fe
    hostname: doris-fe
    environment:
      FE_SERVERS: fe1:doris-fe:9010
      FE_ID: 1
    ports:
      - "8030:8030"
      - "9030:9030"
      - "9010:9010"
    volumes:
      - ./data/doris/fe/meta:/opt/apache-doris/fe/doris-meta
      - ./data/doris/fe/log:/opt/apache-doris/fe/log
    networks:
      default:
        aliases: [ doris-fe ]
    healthcheck:
      test: [ "CMD", "curl", "-f", "http://localhost:8030/api/bootstrap" ]
      interval: 15s; timeout: 10s; retries: 20

  doris-be:
    image: apache/doris:4.1.3-be-ubuntu
    container_name: datanest-doris-be
    hostname: doris-be
    depends_on:
      doris-fe:
        condition: service_healthy
    environment:
      FE_SERVERS: fe1:doris-fe:9010
      BE_ADDR: doris-be:9050
    ports:
      - "9060:9060"
      - "8040:8040"
      - "9050:9050"
    volumes:
      - ./data/doris/be/storage:/opt/apache-doris/be/storage
      - ./data/doris/be/log:/opt/apache-doris/be/log
    networks:
      default:
        aliases: [ doris-be ]
    healthcheck:
      test: [ "CMD", "curl", "-f", "http://localhost:8040/api/health" ]
      interval: 15s; timeout: 10s; retries: 20

  # ============ 后端服务 ============

  system:
    build:
      context: ./data-nest-system
      dockerfile: Dockerfile
    container_name: datanest-system
    depends_on:
      nacos:    { condition: service_healthy }
      postgres: { condition: service_healthy }
    environment:
      NACOS_ADDR: nacos:8848
      PG_HOST: postgres
      PG_PORT: 5432
      PG_USER: datanest
      PG_PASSWORD: ${PG_PASSWORD:-datanest123}
    ports:
      - "8087:8087"
    healthcheck:
      test: [ "CMD", "curl", "-f", "http://localhost:8087/actuator/health" ]
      interval: 15s; timeout: 5s; retries: 10

  gateway:
    build:
      context: ./data-nest-gateway
      dockerfile: Dockerfile
    container_name: datanest-gateway
    depends_on:
      nacos:  { condition: service_healthy }
      system: { condition: service_healthy }
    environment:
      NACOS_ADDR: nacos:8848
      JWT_SECRET: ${JWT_SECRET:-DataNestSecretKey2026!}
    ports:
      - "8080:8080"
    healthcheck:
      test: [ "CMD", "curl", "-f", "http://localhost:8080/actuator/health" ]
      interval: 15s; timeout: 5s; retries: 10

  # ============ 前端 ============
  frontend:
    build:
      context: ./data-nest-frontend
      dockerfile: Dockerfile
    container_name: datanest-frontend
    depends_on:
      gateway: { condition: service_healthy }
    ports:
      - "3000:80"
    healthcheck:
      test: [ "CMD", "curl", "-f", "http://localhost:80/" ]
      interval: 15s; timeout: 5s; retries: 5
```

### 4.3 启动顺序

```
1. nacos
2. postgres
3. doris-fe → doris-be
4. system        ← 等 PG（Flyway 自动建表 + 预置数据）
5. gateway       ← 等 nacos + system（登录接口依赖 system）
6. frontend      ← 等 gateway
```

---

## 5. Gateway + 鉴权框架

### 5.1 架构定位

```
浏览器 (3000) ──▶ gateway-service (8080)
                     │ JwtAuthFilter
                     │
                     ├── POST /api/auth/login        → Gateway 自身，调 system-service 验证→签发 JWT
                     ├── /api/system/**               → lb://system-service
                     └── /actuator/**                 → 放行
```

### 5.2 Gateway application.yml

```yaml
spring:
  application:
    name: gateway-service
  config:
    import:
      - nacos:gateway-service.yaml?refreshEnabled=true
      - nacos:shared-security.yaml?refreshEnabled=true&group=shared-configs
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_ADDR:localhost:8848}
        namespace: datanest-dev
      config:
        server-addr: ${NACOS_ADDR:localhost:8848}
        namespace: datanest-dev
        file-extension: yaml
    gateway:
      default-filters:
        - DedupeResponseHeader=Access-Control-Allow-Origin
      routes:
        - id: system-service
          uri: lb://system-service
          predicates: [ Path=/api/system/** ]
        # 后续 Sprint 按需添加：
        # - id: engineering-service   → Sprint 1
        # - id: governance-service    → Sprint 1
        # - id: data-service          → Sprint 10

server:
  port: 8080
```

> Sa-Token 配置统一放在 Nacos `shared-configs/shared-security.yaml`，通过 `spring.config.import` 加载，本地
> `application.yml` 不再重复声明。

### 5.3 登录时序

```
1. 前端 POST /api/auth/login { username: "admin", password: "admin123" }
2. Gateway AuthController → Feign → system-service POST /api/system/users/verify
3. system-service: 查 DB → 验证密码（BCrypt）→ 返回 UserLoginDTO (含 userId, roles)
4. Gateway: StpUtil.login(userId) → 签发 JWT → 返回 { token, userInfo }
5. 前端存 token → 后续所有请求带 Authorization header
6. JwtAuthFilter 校验 JWT → 透传 X-User-Id → 路由到目标服务
```

### 5.4 Gateway 登录接口代码

```java
// Gateway AuthController
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final SystemServiceClient systemClient;

    @PostMapping("/login")
    public Mono<Map<String, Object>> login(@RequestBody LoginRequest req) {
        // 1. 调 system-service 验证账户
        UserLoginDTO user = systemClient.verifyCredentials(req);
        if (user == null) {
            return Mono.error(new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
        }
        if (!user.isEnabled()) {
            return Mono.error(new ResponseStatusException(
                HttpStatus.FORBIDDEN, "账号已被禁用"));
        }
        // 2. 签发 JWT
        StpUtil.login(user.getUserId());
        // 3. 返回 Token + 用户信息
        return Mono.just(Map.of(
            "token", StpUtil.getTokenValue(),
            "tokenName", "Authorization",
            "userInfo", Map.of(
                "userId", user.getUserId(),
                "username", user.getUsername(),
                "roles", user.getRoles(),
                "permissions", user.getPermissions()
            )
        ));
    }
}

// Feign Client
@FeignClient(name = "system-service", path = "/api/system")
interface SystemServiceClient {
    @PostMapping("/users/verify")
    UserLoginDTO verifyCredentials(@RequestBody LoginRequest request);
}
```

### 5.5 JWT 过滤器

```java
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    private static final List<String> WHITELIST = List.of(
        "/api/auth/login", "/actuator/health", "/actuator/info"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (WHITELIST.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }

        String token = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (token == null || token.isBlank()) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        try {
            Object loginId = StpUtil.getLoginIdByToken(token);
            exchange.getRequest().mutate()
                .header("X-User-Id", String.valueOf(loginId));
        } catch (Exception e) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }
}
```

---

## 6. 用户与权限管理（system-service）

### 6.1 职责边界

| 功能                     | 调用方                   | 鉴权                          |
|--------------------------|--------------------------|-------------------------------|
| `POST /users/verify`     | Gateway（Feign）         | Internal Token                |
| `GET /users`             | 前端（管理员）           | `@SaCheckRole("SUPER_ADMIN")` |
| `POST /users`            | 前端（管理员）           | `@SaCheckRole("SUPER_ADMIN")` |
| `PUT /users/{id}`        | 前端（管理员）           | `@SaCheckRole("SUPER_ADMIN")` |
| `PUT /users/{id}/status` | 前端（管理员）           | `@SaCheckRole("SUPER_ADMIN")` |
| `GET /roles`             | 前端（admin + 普通用户） | `@SaCheckLogin`               |

### 6.2 4 个预置角色

| 角色代码           | 显示名     | 可见菜单                                                 |
|--------------------|------------|----------------------------------------------------------|
| `SUPER_ADMIN`      | 超级管理员 | 全部（含系统管理）                                       |
| `DATA_ENGINEER`    | 数据工程师 | 数据集成、数据开发、数据治理（查看）、资产目录、数据服务 |
| `DATA_ANALYST`     | 数据分析师 | 资产目录、数据服务（SQL 终端）                           |
| `GOVERNANCE_ADMIN` | 治理管理员 | 数据治理（全部）、资产目录                               |

### 6.3 权限矩阵（API 层）

| API 路径前缀          | SUPER_ADMIN | DATA_ENGINEER | DATA_ANALYST | GOV_ADMIN |
|-----------------------|:-----------:|:-------------:|:------------:|:---------:|
| `/api/system/**`      |   ✅ 全部   |      ❌       |      ❌      |    ❌     |
| `/api/integration/**` |     ✅      |      ✅       |      ❌      |    ❌     |
| `/api/dev/**`         |     ✅      |      ✅       |      ❌      |    ❌     |
| `/api/governance/**`  |     ✅      |     查看      |     查看     |    ✅     |
| `/api/catalog/**`     |     ✅      |      ✅       |      ✅      |    ✅     |
| `/api/data/**`        |     ✅      |      ✅       |      ✅      |    ❌     |
| `/api/realtime/**`    |     ✅      |      ✅       |      ❌      |    ❌     |

### 6.4 核心接口定义

```java
// UserController.java
@RestController
@RequestMapping("/api/system")
public class UserController {

    private final UserService userService;

    /** 内部接口：验证登录凭据 */
    @PostMapping("/users/verify")
    public Result<UserLoginDTO> verifyCredentials(@RequestBody LoginVerifyRequest req) {
        return Result.ok(userService.verifyCredentials(req.getUsername(), req.getPassword()));
    }

    /** 用户列表（搜索 + 分页 + 角色筛选 + 状态筛选）*/
    @GetMapping("/users")
    @SaCheckRole("SUPER_ADMIN")
    public Result<PageResult<UserDTO>> listUsers(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(userService.listUsers(keyword, role, status, page, size));
    }

    /** 创建用户 */
    @PostMapping("/users")
    @SaCheckRole("SUPER_ADMIN")
    public Result<UserDTO> createUser(@RequestBody @Valid UserCreateRequest req) {
        return Result.ok(userService.createUser(req));
    }

    /** 编辑用户 */
    @PutMapping("/users/{id}")
    @SaCheckRole("SUPER_ADMIN")
    public Result<UserDTO> updateUser(@PathVariable Long id, @RequestBody UserUpdateRequest req) {
        return Result.ok(userService.updateUser(id, req));
    }

    /** 禁用/启用用户 */
    @PutMapping("/users/{id}/status")
    @SaCheckRole("SUPER_ADMIN")
    public Result<Void> toggleStatus(@PathVariable Long id, @RequestBody StatusRequest req) {
        userService.toggleStatus(id, req.isEnabled());
        return Result.ok(null);
    }

    /** 修改自己的密码 */
    @PutMapping("/users/password")
    @SaCheckLogin
    public Result<Void> changePassword(@RequestBody ChangePasswordRequest req) {
        Long userId = StpUtil.getLoginIdAsLong();
        userService.changePassword(userId, req.getOldPassword(), req.getNewPassword());
        return Result.ok(null);
    }

    /** 管理员重置用户密码 */
    @PutMapping("/users/{id}/reset-password")
    @SaCheckRole("SUPER_ADMIN")
    public Result<Void> resetPassword(@PathVariable Long id, @RequestBody ResetPasswordRequest req) {
        userService.resetPassword(id, req.getNewPassword());
        return Result.ok(null);
    }
}
```

### 6.5 DTO 关键字段

```java
// LoginVerifyRequest
public record LoginVerifyRequest(String username, String password) {}

// UserLoginDTO（Gateway 登录接口返回值）
public record UserLoginDTO(
    Long userId,
    String username,
    boolean enabled,
    List<String> roles,
    List<String> permissions
) {}

// UserCreateRequest
public record UserCreateRequest(
    @NotBlank @Size(min=3, max=30) String username,
    @NotBlank @Size(min=6, max=20) String password,
    @NotNull List<String> roles,
    @Email String email,
    String phone
) {}
```

### 6.6 密码安全

```java
// 使用 BCrypt 加密，不存明文
@Service
public class UserService {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public UserLoginDTO verifyCredentials(String username, String rawPassword) {
        User user = userMapper.selectByUsername(username);
        if (user == null) return null;
        if (!encoder.matches(rawPassword, user.getPasswordHash())) return null;
        // 返回不含密码的用户信息
        return toLoginDTO(user);
    }

    public UserDTO createUser(UserCreateRequest req) {
        User user = new User();
        user.setUsername(req.getUsername());
        user.setPasswordHash(encoder.encode(req.getPassword()));  // BCrypt
        user.setEnabled(true);
        userMapper.insert(user);
        // 关联角色...
        return toDTO(user);
    }
}
```

---

## 7. 数据库设计（Flyway 迁移）

### 7.1 迁移策略

Flyway 迁移脚本放在 `data-nest-system/src/main/resources/db/migration/`。system-service 启动时自动执行。

### 7.2 V1.0.0__init_user_tables.sql

```sql
-- ===== 用户表 =====
CREATE TABLE IF NOT EXISTS sys_user (
    id          BIGINT PRIMARY KEY,
    username    VARCHAR(30)  NOT NULL,
    password_hash VARCHAR(200) NOT NULL,
    email       VARCHAR(100),
    phone       VARCHAR(20),
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login  TIMESTAMP,
    CONSTRAINT uk_username UNIQUE (username)
);

COMMENT ON TABLE sys_user IS '系统用户';
COMMENT ON COLUMN sys_user.id IS '雪花主键';
COMMENT ON COLUMN sys_user.password_hash IS 'BCrypt 哈希';

-- ===== 角色表 =====
CREATE TABLE IF NOT EXISTS sys_role (
    id          BIGINT PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL,
    name        VARCHAR(50)  NOT NULL,
    description VARCHAR(200),
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_role_code UNIQUE (code)
);

COMMENT ON TABLE sys_role IS '系统角色';

-- ===== 用户-角色关联表 =====
CREATE TABLE IF NOT EXISTS sys_user_role (
    user_id BIGINT NOT NULL REFERENCES sys_user(id),
    role_id BIGINT NOT NULL REFERENCES sys_role(id),
    PRIMARY KEY (user_id, role_id)
);

-- ===== 权限表 =====
CREATE TABLE IF NOT EXISTS sys_permission (
    id          BIGINT PRIMARY KEY,
    code        VARCHAR(100) NOT NULL,
    name        VARCHAR(100) NOT NULL,
    resource    VARCHAR(200),         -- API 路径模式，如 /api/integration/**
    action      VARCHAR(20),           -- GET, POST, PUT, DELETE, *
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_perm_code UNIQUE (code)
);

COMMENT ON TABLE sys_permission IS '权限定义';

-- ===== 角色-权限关联表 =====
CREATE TABLE IF NOT EXISTS sys_role_permission (
    role_id       BIGINT NOT NULL REFERENCES sys_role(id),
    permission_id BIGINT NOT NULL REFERENCES sys_permission(id),
    PRIMARY KEY (role_id, permission_id)
);
```

### 7.3 V1.0.1__seed_roles_and_admin.sql

```sql
-- ===== 预置角色 =====
INSERT INTO sys_role (id, code, name, description) VALUES
(1, 'SUPER_ADMIN',      '超级管理员',  '全部权限，可管理用户和角色'),
(2, 'DATA_ENGINEER',    '数据工程师',  '数据集成、开发、治理查看、资产目录、数据服务'),
(3, 'DATA_ANALYST',     '数据分析师',  '资产目录、数据服务（SQL 终端）'),
(4, 'GOVERNANCE_ADMIN', '治理管理员',  '数据治理全部功能、资产目录');

-- ===== 预置权限 =====
INSERT INTO sys_permission (id, code, name, resource, action) VALUES
(1,  'system:*',         '系统管理',     '/api/system/**',  '*'),
(2,  'integration:*',    '数据集成',     '/api/integration/**', '*'),
(3,  'dev:*',            '数据开发',     '/api/dev/**',     '*'),
(4,  'governance:*',     '数据治理全部',  '/api/governance/**', '*'),
(5,  'governance:read',  '数据治理查看',  '/api/governance/**', 'GET'),
(6,  'catalog:*',        '资产目录',     '/api/catalog/**', '*'),
(7,  'data:*',           '数据服务',     '/api/data/**',    '*'),
(8,  'realtime:*',       '实时计算',     '/api/realtime/**', '*');

-- ===== 角色-权限关联 =====
-- 超级管理员：全部权限
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(1,1), (1,2), (1,3), (1,4), (1,5), (1,6), (1,7), (1,8);

-- 数据工程师
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(2,2), (2,3), (2,5), (2,6), (2,7), (2,8);

-- 数据分析师
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(3,5), (3,6), (3,7);

-- 治理管理员
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(4,4), (4,6);

-- ===== 预置管理员账号 ====
INSERT INTO sys_user (id, username, password_hash, email, enabled) VALUES
(10001, 'admin',
 '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi',  -- admin123
 'admin@datanest.io', TRUE);

-- 关联超级管理员角色
INSERT INTO sys_user_role (user_id, role_id) VALUES (10001, 1);
```

> ⚠️ BCrypt hash 是预计算的：`$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi` = `admin123`。生产部署时通过启动脚本替换。

---

## 8. 共享配置设计

### 8.1 配置一览

| 配置文件                 | 内容              | 消费服务                        |
|--------------------------|-------------------|---------------------------------|
| `shared-datasource.yaml` | PG 连接信息       | system、governance、integration |
| `shared-security.yaml`   | JWT Secret、CORS  | gateway                         |
| `shared-doris.yaml`      | Doris FE/BE 地址  | integration、dev、governance    |

### 8.2 shared-datasource.yaml

```yaml
spring:
  datasource:
    driver-class-name: org.postgresql.Driver
    url: jdbc:postgresql://${PG_HOST:localhost}:${PG_PORT:5432}/datanest
    username: ${PG_USER:datanest}
    password: ${PG_PASSWORD:datanest123}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
```

### 8.3 shared-security.yaml

```yaml
sa-token:
  jwt-secret-key: ${JWT_SECRET:DataNestSecretKey2026!ChangeMeInProduction}
  # 统一从 Authorization Header 读取 token，不额外配置 token-prefix
  token-name: Authorization
  timeout: 604800
  active-timeout: 1800
  is-concurrent: true
  is-share: true
  token-style: tik
  is-read-header: true
  is-read-cookie: false
  is-log: true

spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

datanest:
  security:
    cors:
      allowed-origins: ${CORS_ORIGINS:http://localhost:3000}
      allowed-methods: GET,POST,PUT,DELETE,OPTIONS
      allowed-headers: Authorization,Content-Type,X-User-Id
      allow-credentials: true
      max-age: 3600
```

### 8.4 shared-doris.yaml

```yaml
datanest:
  doris:
    fe:
      host: ${DORIS_FE_HOST:localhost}
      http-port: 8030
      jdbc-port: 9030
    jdbc:
      url: jdbc:mysql://${DORIS_FE_HOST:localhost}:9030
      user: root
      password: ${DORIS_PASSWORD:}
```

---

## 9. 公共模块设计

`data-nest-common` 纯 Java 库，所有微服务依赖。Sprint 0 需要以下类：

```
com.datanest.common
├── model/
│   ├── Result.java           # 统一响应体 Result<T>
│   ├── PageResult.java       # 分页结果
│   ├── DataSourceType.java   # 枚举：MYSQL, POSTGRESQL, DORIS...
│   ├── TableRef.java         # 表引用
│   └── SyncMode.java         # 同步模式
├── event/
│   ├── DomainEvent.java      # 事件基类
│   ├── LineageEvent.java     # 血缘事件
│   └── MetadataChangeEvent.java
├── exception/
│   ├── ErrorCode.java        # 错误码枚举
│   └── BusinessException.java
└── util/
    └── SqlUtils.java
```

---

## 10. 前端工程骨架

### 10.1 页面结构

```
src/
├── main.tsx                     # React 入口
├── App.tsx                      # 路由 + 鉴权守卫
├── pages/
│   ├── login/                   # 登录页（首页）
│   │   └── index.tsx
│   ├── home/                    # 首页骨架
│   │   └── index.tsx
│   └── system/
│       └── users/               # 用户管理
│           └── index.tsx
├── components/
│   ├── Layout.tsx               # 通用布局（侧边栏 + 顶栏）
│   └── Sidebar.tsx              # 动态菜单（按角色显隐）
├── store/
│   └── useAuthStore.ts          # Zustand：token / userInfo
├── api/
│   ├── request.ts               # axios 封装（自动带 Token）
│   └── auth.ts                  # login/logout API
└── router/
    └── index.tsx                # React Router 路由定义
```

### 10.2 登录页

```tsx
// pages/login/index.tsx 关键逻辑
const handleLogin = async () => {
    const res = await authApi.login({ username, password });
    // 存 token + userInfo
    useAuthStore.getState().setToken(res.data.token);
    useAuthStore.getState().setUserInfo(res.data.userInfo);
    // 跳转首页
    navigate('/home');
};
```

### 10.3 动态菜单（按角色）

```tsx
// components/Sidebar.tsx
const menuConfig: Record<string, MenuItem[]> = {
    SUPER_ADMIN: [
        { key: 'home', label: '首页', path: '/home' },
        { key: 'system', label: '系统管理', children: [
            { key: 'users', label: '用户管理', path: '/system/users' },
        ]},
    ],
    DATA_ENGINEER: [
        { key: 'home', label: '首页', path: '/home' },
    ],
    DATA_ANALYST: [
        { key: 'home', label: '首页', path: '/home' },
    ],
    GOVERNANCE_ADMIN: [
        { key: 'home', label: '首页', path: '/home' },
    ],
    // 后续 Sprint 按角色加菜单项
};

function Sidebar() {
    const roles = useAuthStore(s => s.userInfo?.roles || []);
    const menus = roles.flatMap(r => menuConfig[r] || []);
    // 去重渲染...
}
```

### 10.4 路由鉴权守卫

```tsx
// App.tsx
function ProtectedRoute({ children }: { children: ReactNode }) {
    const token = useAuthStore(s => s.token);
    if (!token) return <Navigate to="/login" replace />;
    return <>{children}</>;
}

function App() {
    return (
        <BrowserRouter>
            <Routes>
                <Route path="/login" element={<LoginPage />} />
                <Route path="/*" element={
                    <ProtectedRoute>
                        <Layout>
                            <Outlet />
                        </Layout>
                    </ProtectedRoute>
                }>
                    <Route path="home" element={<HomePage />} />
                    <Route path="system/users" element={<UserManage />} />
                    {/* 后续 Sprint 按需加路由 */}
                </Route>
            </Routes>
        </BrowserRouter>
    );
}
```

### 10.5 Dockerfile + nginx.conf

```dockerfile
# 多阶段构建
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

```nginx
server {
    listen 80;
    root /usr/share/nginx/html;
    index index.html;
    location / {
        try_files $uri $uri/ /index.html;   # SPA 回退
    }
    location /api/ {
        proxy_pass http://gateway:8080;
        proxy_set_header Host $host;
    }
}
```

---

## 11. 本地开发环境

### 11.1 一键启动脚本

```bash
#!/bin/bash
# start-local.sh — Sprint 0 开发环境一键启动
set -e

echo "🐣 DataNest Sprint 0 — 启动开发环境"

# 1. 编译全部后端
echo "🔨 1/4 编译..."
mvn clean install -DskipTests -T 4

# 2. 启动中间件
echo "📦 2/4 启动中间件..."
docker compose up -d nacos postgres doris-fe doris-be

echo "⏳ 等待 Nacos..."
until curl -s http://localhost:8848/nacos/v1/console/health/readiness > /dev/null 2>&1; do sleep 3; done

# 3. 导入 Nacos 配置
echo "⚙️ 3/4 导入 Nacos 配置..."
for f in shared-configs/*.yaml; do
    data_id=$(basename "$f" .yaml)
    curl -s -X POST "http://localhost:8848/nacos/v1/cs/configs" \
        -d "dataId=${data_id}.yaml" \
        -d "group=shared-configs" \
        -d "content=$(cat "$f")" \
        -d "type=yaml" > /dev/null
    echo "  ✔ ${data_id}.yaml"
done

# 4. 启动全部服务
echo "🚀 4/4 启动全栈..."
docker compose up -d --build

echo ""
echo "🎉 Sprint 0 就绪！"
echo "  前端:    http://localhost:3000"
echo "  Gateway: http://localhost:8080"
echo "  Nacos:   http://localhost:8848/nacos"
echo "  Doris:   jdbc:mysql://localhost:9030"
echo ""
echo "  管理员账号: admin / admin123"
```

### 11.2 IDE 开发模式

日常开发：中间件用 Docker，前后端用 IDE 直接跑。

```bash
# 终端 1：只起中间件
docker compose up -d nacos postgres doris-fe doris-be

# 终端 2：前端热更新开发
cd data-nest-frontend && npm run dev    # port 5173 → proxy /api → 8080

# IDE：逐个启动后端服务
# system-service → gateway → 空壳服务
```

---

## 12. Sprint 0 ADR

### ADR-S0-001: 中间件按需引入

| 项目     | 内容                                                                                                           |
|----------|----------------------------------------------------------------------------------------------------------------|
| **状态** | Accepted                                                                                                       |
| **决策** | Sprint 0 只装 Nacos + PostgreSQL + Doris。Redis / OpenSearch / Neo4j / DS / MinIO / Flink 等有明确消费方时再加 |
| **后果** | 📈 部署轻量（3 容器）；📉 后续 Sprint 需改 docker-compose.yml                                                  |

### ADR-S0-002: Sa-Token 内存模式

| 项目     | 内容                                                              |
|----------|-------------------------------------------------------------------|
| **状态** | Accepted                                                          |
| **决策** | 先用内存模式，Sprint 1-2 引入 Redis 后切 `sa-token-redis-jackson` |
| **后果** | 📈 少一个中间件；📉 Gateway 重启后 Token 全失效                   |

### ADR-S0-003: system-service 独立 vs 嵌入 gateway

| 项目         | 内容                                                                                                  |
|--------------|-------------------------------------------------------------------------------------------------------|
| **状态**     | Accepted                                                                                              |
| **上下文**   | 用户管理可以放在 Gateway（已有 Sa-Token）或独立服务                                                   |
| **决策**     | **独立 `data-nest-system` 服务**。Gateway 只做路由+鉴权，用户 CRUD 在 system-service，通过 Feign 调用 |
| **替代方案** | 嵌入 Gateway——但 Gateway 是 WebFlux，与 JDBC/MyBatis-Plus 混用不自然                                  |
| **后果**     | 📈 职责清晰，Gateway 保持薄；📉 多一个服务，登录链路多一次 Feign 调用                                 |

### ADR-S0-004: 前端独立容器

| 项目     | 内容                                                        |
|----------|-------------------------------------------------------------|
| **状态** | Accepted                                                    |
| **决策** | 独立 Nginx 容器（port 3000），`proxy_pass /api/` 到 Gateway |
| **后果** | 📈 前后端完全解耦；📉 多一个容器                            |

### ADR-S0-005: 模块按需建——不在 Sprint 0 建空壳

| 项目       | 内容                                                                                                                                                 |
|------------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| **状态**   | Accepted                                                                                                                                             |
| **上下文** | 总技术架构 v2.2 规划了 8 个微服务，经过业务域分析合并为 **5 个**（见 ADR-S0-007）。Sprint 0 只需要 gateway + system，其余 3 个后续 Sprint 按需添加   |
| **决策**   | **只建目前有代码的 3 个模块**（common + gateway + system）。integration / dev 等业务微服务在对应的后续 Sprint 中按需添加                             |
| **后果**   | 📈 编译快、容器少、认知负担低，原则就是"做到哪建到哪"；📉 后续 Sprint 添加新模块时需要改 Root POM 的 `<modules>` 和 docker-compose.yml，但改动量很小 |

### ADR-S0-006: Flyway 嵌入 system-service vs 独立服务 🆕

| 项目         | 内容                                                                                               |
|--------------|----------------------------------------------------------------------------------------------------|
| **状态**     | Accepted                                                                                           |
| **上下文**   | 需要 user/role/permission 表初始化                                                                 |
| **决策**     | Flyway 迁移脚本放在 system-service 内部，随服务启动自动执行                                        |
| **替代方案** | 独立 db-migration 服务——但对于 4 张表来说过度设计                                                  |
| **后果**     | 📈 无额外服务，迁移与服务强绑定，不会出现版本不一致；📉 后续其他服务需要自己的表时可能需要调整策略 |

### ADR-S0-007: 微服务合并——8 → 5 🆕

| 项目       | 内容                                                                                                                                                                                                                            |
|------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **状态**   | Accepted                                                                                                                                                                                                                        |
| **上下文** | 总技术架构 v2.2 拆了 8 个微服务。分析发现：integration-governance-catalog 共享 PG、"采集→治理→展示"是一条链路硬拆成 3 次 RPC；system / data-service 代码量不到 500 行不配独立进程；开源项目初期 1-3 人团队人均 3-4 个服务不现实 |
| **决策**   | 按业务域内聚合并为 **5 个微服务**：                                                                                                                                                                                             |
|            | **gateway** — 路由 + JWT 鉴权（WebFlux，不可合并）                                                                                                                                                                              |
|            | **system** — 用户/角色/权限管理                                                                                                                                                                                                 |
|            | **data-engineering** — integration + dev + realtime（数据工程一条链）                                                                                                                                                           |
|            | **data-governance** — governance + catalog（治理 + 资产目录）                                                                                                                                                                   |
|            | **data-service** — SQL 终端 + API 生成                                                                                                                                                                                          |
| **后果**   | 📈 从 8 减到 5，团队认知负担大幅降低；📈 同域内调用变进程内方法调用（性能↑、调试↓）；📉 data-engineering 和 data-governance 单服务体量较大，需在内部做好包结构分层防止耦合                                                      |

---

## 13. 验收标准

| #  | 验收项                                                         | 验证                |
|----|----------------------------------------------------------------|---------------------|
| ✅ | `mvn clean install` 全量编译通过                               | 命令行              |
| ✅ | `docker compose up -d` 全部容器 healthy                        | `docker compose ps` |
| ✅ | Nacos Console 可访问，4 个 shared-configs 可见                 | 浏览器              |
| ✅ | `POST /api/auth/login` (admin/admin123) 返回 JWT + userInfo    | curl                |
| ✅ | 用 Token 调用 `GET /api/system/users` 返回用户列表（含 admin） | curl                |
| ✅ | 未登录访问 `/api/system/users` 返回 401                        | curl                |
| ✅ | 用 admin 账号创建用户 `zhangw`（角色=数据工程师）→ 成功        | curl 或前端         |
| ✅ | 用 `zhangw` 登录 → Token 中 roles 含 `DATA_ENGINEER`           | curl 或前端         |
| ✅ | 前端 `http://localhost:3000` → 未登录跳转登录页                | 浏览器              |
| ✅ | admin 登录后左侧菜单含「系统管理」→ 用户管理页可操作用户 CRUD  | 浏览器              |
| ✅ | zhangw 登录后左侧菜单无「系统管理」                            | 浏览器              |
| ✅ | `start-local.sh` 一键启动通过                                  | 执行脚本            |

---

## 14. 风险与对策

| #  | 风险                                                     | 概率 | 影响 | 对策                                                                    |
|----|----------------------------------------------------------|------|------|-------------------------------------------------------------------------|
| R1 | Doris 镜像首次拉取慢（~2GB）                             | 高   | 中   | 提供离线包；国内镜像加速                                                |
| R2 | Nacos 3.1.1 + Spring Boot 4.0.7 兼容性                   | 中   | 高   | 第一天就验证注册+配置核心链路                                           |
| R3 | admin 初始密码写在 SQL 中                                | —    | 高   | 首次部署后弹窗强制修改；CI 中用环境变量注入                             |
| R4 | Mac M 芯片 Docker 兼容                                   | 中   | 中   | 提供 `platform: linux/amd64` 备选说明                                   |
| R5 | 本地 16GB RAM 不够（Doris + PG + 2 个 JVM 微服务）       | 低   | —    | 日常只起中间件，后端 IDE 跑，不需全打 Docker                            |
| R6 | Gateway WebFlux + Feign 调用 system-service 的阻塞兼容性 | 低   | 中   | Feign 是阻塞调用，Gateway 需在 WebFlux 中正确使用 `block()`，已验证兼容 |

---

## 附录 A：端口速查

| 端口 | 服务               | 说明           |
|------|--------------------|----------------|
| 3000 | frontend (Nginx)   | React SPA      |
| 8080 | gateway-service    | API 入口       |
| 8087 | **system-service** | 用户权限管理   |
| 8848 | Nacos              | 控制台         |
| 5432 | PostgreSQL         | 元数据库       |
| 9030 | Doris JDBC         | MySQL Protocol |

> 8082 / 8083 / 8085 端口预留给后续 Sprint 的 engineering-service / governance-service / data-service。

## 附录 B：修订记录

| 版本 | 日期       | 修订内容                                                                                                      | 作者       |
|------|------------|---------------------------------------------------------------------------------------------------------------|------------|
| v1.0 | 2026-07-23 | 初始版本                                                                                                      | 软件架构师 |
| v1.1 | 2026-07-23 | 精简：去 CI/CD、中间件减到 3 个、Docker Compose 加前后端                                                      | 软件架构师 |
| v1.2 | 2026-07-23 | 集成用户管理 PRD：新增 system-service、Flyway 迁移、4 预置角色、登录页、RBAC                                  | 软件架构师 |
| v1.3 | 2026-07-23 | 精简：Maven 模块从 9 减到 4（去空壳）、Docker Compose 只起 gateway+system+frontend、路由只保留 /api/system/** | 软件架构师 |
| v1.4 | 2026-07-23 | 架构合并 8→5：新增 ADR-S0-007；更新 POM/Routes/Ports 注释                                                     | 软件架构师 |
| v1.5 | 2026-07-23 | 版本统一：PG16/Doris4.1.3/system 端口 8087；菜单去掉角色管理（权限在 DB、无界面）                             | 软件架构师 |