# DataNest 技术架构文档 v2.3

> **文档状态**：Sprint 0 参考架构 | **作者**：软件架构师 | **关联文档**：`DataNest-产品规格文档-v2.0.md`
>
> v2.3：微服务 8→5 合并（engineering / governance 域内聚）+ system-service + Flyway 嵌入

---

## 目录

1. [版本依赖矩阵](#1-版本依赖矩阵)
2. [架构全景（C4 Level 1）](#2-架构全景c4-level-1)
3. [微服务拆分（C4 Level 2）](#3-微服务拆分c4-level-2)
4. [服务间通信](#4-服务间通信)
5. [Nacos 配置中心设计](#5-nacos-配置中心设计)
6. [核心数据流](#6-核心数据流)
7. [各微服务详细设计](#7-各微服务详细设计)
8. [Doris 存储引擎集成](#8-doris-存储引擎集成)
9. [Flink CDC 实时计算集成](#9-flink-cdc-实时计算集成)
10. [部署拓扑](#10-部署拓扑)
11. [关键架构决策（ADR）](#11-关键架构决策adr)
12. [代码仓库结构](#12-代码仓库结构)
13. [演进策略](#13-演进策略)

---

## 1. 版本依赖矩阵

### 1.1 核心版本（已锁定）

| 组件                     | 版本               | 说明                                                |
|--------------------------|--------------------|-----------------------------------------------------|
| **JDK**                  | 21 LTS             | Spring Boot 4.0 最低要求                            |
| **Spring Boot**          | 4.0.7              | 基于 Jakarta EE 10                                  |
| **Spring Cloud**         | 2025.1.2 (Oakwood) | 官方适配 Spring Boot 4.0.7                          |
| **Spring Cloud Alibaba** | 2025.1.0.0         | 内置 Nacos Client 3.1.1                             |
| **Nacos Server**         | 3.1.1              | 注册中心 + 配置中心                                 |
| **Apache Doris**         | 4.1.3              | OLAP 查询引擎，FE + BE 内嵌（2026-07-13 最新）      |
| **Apache Iceberg**       | 1.11.0             | 湖仓表格式，JDBC Catalog（2026-05-19 最新）         |
| **Apache Flink**         | 2.1.3              | CDC Connector + Iceberg Sink（兼容 Iceberg 1.11.0） |
| **DolphinScheduler**     | 3.4.2              | DAG 可视化编排 + 调度引擎（2026-05-30 最新）        |
| **Addax**                | 6.0.11             | 批量数据同步引擎（2026-05-17 最新）                 |
| **Neo4j**                | 5.x Community      | 血缘关系图存储                                      |
| **PostgreSQL**           | 16                 | 元数据 + Iceberg JDBC Catalog                       |
| **Redis**                | 7.x                | Sa-Token 会话 + 分布式锁 + 缓存                     |
| **MinIO**                | RELEASE.2024       | S3 兼容，Iceberg 数据文件存储                       |
| **OpenSearch**           | 2.x                | 数据资产搜索索引                                    |
| **Sa-Token**             | 1.40+              | 轻量级权限认证框架（Redis 集成）                    |
| **MyBatis-Plus**         | 3.5.x              | ORM 框架，雪花主键                                  |
| **Flyway**               | 10.x               | 数据库版本迁移（独立 db-migration 服务）            |
| **构建工具**             | Maven 3.9+         | 多模块父子 POM                                      |

### 1.2 依赖坐标速查

```xml
<!-- 父 POM 版本管理 -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.0.7</version>
</parent>

<dependencyManagement>
<dependencies>
    <!-- Spring Cloud BOM -->
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-dependencies</artifactId>
        <version>2025.1.2</version>
        <type>pom</type>
        <scope>import</scope>
    </dependency>
    <!-- Spring Cloud Alibaba BOM -->
    <dependency>
        <groupId>com.alibaba.cloud</groupId>
        <artifactId>spring-cloud-alibaba-dependencies</artifactId>
        <version>2025.1.0.0</version>
        <type>pom</type>
        <scope>import</scope>
    </dependency>
</dependencies>
</dependencyManagement>

        <!-- 各服务按需引入 -->
        <!-- 注册发现 -->
<dependency>
<groupId>com.alibaba.cloud</groupId>
<artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>
        <!-- 配置中心 -->
<dependency>
<groupId>com.alibaba.cloud</groupId>
<artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
</dependency>
        <!-- 服务调用 -->
<dependency>
<groupId>org.springframework.cloud</groupId>
<artifactId>spring-cloud-starter-openfeign</artifactId>
</dependency>
        <!-- 负载均衡 -->
<dependency>
<groupId>org.springframework.cloud</groupId>
<artifactId>spring-cloud-starter-loadbalancer</artifactId>
</dependency>
```

> ⚠️ **重要**：Spring Boot 4.0 / Spring Cloud Alibaba 2025.1.0.0 已废弃 `bootstrap.yml`，Nacos 配置改用
> `spring.config.import` 机制。详见第 5 章。

---

## 2. 架构全景（C4 Level 1）

```
                              ┌──────────────────────┐
                              │    Nacos 3.1.1        │
                              │  Registry & Config    │
                              └──────┬───────────────┘
                                     │ 注册/发现 + 配置拉取
                                     │
┌────────────────────────────────────┼──────────────────────────────────────┐
│                            DataNest Platform                                │
│                                    │                                        │
│  ┌──────────┐  ┌──────────┐  ┌───┴──────┐  ┌──────────┐  ┌──────────┐    │
│  │Gateway   │  │ System   │  │Engineering│  │Governance│  │  Data    │    │
│  │Service   │  │ Service  │  │ Service   │  │ Service  │  │ Service  │    │
│  └────┬─────┘  └────┬─────┘  └────┬──────┘  └────┬─────┘  └────┬─────┘    │
│       │             │             │              │             │           │
│       └─────────────┴─────────────┴──────────────┴─────────────┘           │
│                                  │                                         │
│  ┌───────────────────────────────┼──────────────────────────────────┐     │
│  │                   Storage & Compute Layer                        │     │
│  │  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────┐  │     │
│  │  │   Apache Doris    │  │  Apache Iceberg  │  │ Flink CDC    │  │     │
│  │  │  (OLAP 查询引擎)  │  │  (湖仓表格式)    │  │ (MiniCluster)│  │     │
│  │  │  FE + BE 内嵌    │  │  JDBC Catalog    │  │ CDC→Iceberg  │  │     │
│  │  └──────────────────┘  └────────┬─────────┘  └──────────────┘  │     │
│  │                                 │ 数据文件                       │     │
│  │                      ┌──────────▼──────────┐                    │     │
│  │                      │   MinIO / Local FS  │                    │     │
│  │                      │  (Iceberg 数据存储)  │                    │     │
│  │                      └─────────────────────┘                    │     │
│  └──────────────────────────────────────────────────────────────────┘     │
│                                  │                                         │
│  ┌───────────────────────────────┼──────────────────────────────────┐     │
│  │                      Infrastructure                               │     │
│  │  PostgreSQL 16  ·  Neo4j 5.x  ·  OpenSearch 2.x  ·  Sa-Token    │     │
│  └──────────────────────────────────────────────────────────────────┘     │
└───────────────────────────────────────────────────────────────────────────┘

外部系统:  [业务DB (MySQL)]  [数据湖 (HDFS/S3)]  [BI 工具 (Superset)]
                ▲ 接入              ▲ 存储              ▲ 消费
```

### 2.1 与 v1.0 的核心差异

| 维度     | v1.0（废弃）                   | v2.0（当前）                              |
|----------|--------------------------------|-------------------------------------------|
| 架构     | 模块化单体                     | 微服务（Nacos 注册发现）                  |
| 构建     | Gradle                         | Maven（父子 POM）                         |
| OLAP     | 自研列存引擎                   | Apache Doris 内嵌                         |
| 流处理   | 自研 Stateful Stream Processor | Flink CDC（MiniCluster 内嵌）             |
| 配置     | Spring Config                  | Nacos 配置中心（shared-configs 粒度切分） |
| 服务通信 | 进程内调用                     | OpenFeign + LoadBalancer                  |

---

## 3. 微服务拆分（C4 Level 2）

> **v2.3 修订**：原 8 个微服务按业务域内聚合并为 **5 个**。核心逻辑：integration+dev+realtime 是一条链上共享 PG→合并为
> engineering；governance+catalog 全链路治理→合并为 governance。详细决策记录见 ADR-011。

### 3.1 服务清单

| 服务名                  | 端口 | 职责                                                   | Sprint    |
|-------------------------|------|--------------------------------------------------------|-----------|
| **gateway-service**     | 8080 | 统一入口，Sa-Token JWT 鉴权，路由转发                  | Sprint 0  |
| **system-service**      | 8087 | 用户/角色/权限管理，RBAC                               | Sprint 0  |
| **engineering-service** | 8082 | 数据源管理 + 批量同步 + DAG 编排 + 调度引擎 + CDC 管道 | Sprint 1  |
| **governance-service**  | 8084 | 元数据 + 血缘 + 质量规则 + 数据标准 + 资产目录 + 搜索  | Sprint 1  |
| **data-service**        | 8085 | SQL 终端 + API 生成                                    | Sprint 10 |

> 注：Doris、DolphinScheduler、Flink 是嵌入式引擎，不单独部署为服务。Flyway 迁移嵌入 system-service，不独立部署。

### 3.2 服务依赖关系

```
gateway-service (8080)
  ├── system-service (8087)
  │     └── PostgreSQL（用户/角色数据）
  ├── engineering-service (8082)
  │     ├── PostgreSQL（数据源 + 任务元数据）
  │     └── Doris (FE: 9030, BE: 9060)
  ├── governance-service (8084)
  │     ├── PostgreSQL（元数据）+ Neo4j（血缘）+ OpenSearch（搜索）
  │     └── Doris (FE: 9030, BE: 9060)
  └── data-service (8085)
        └── Doris (FE: 9030, BE: 9060)
```

### 3.3 合并对比

| 原 8 服务                    | 新 5 服务       | 合并理由                                                  |
|------------------------------|-----------------|-----------------------------------------------------------|
| integration + dev + realtime | **engineering** | 共用 PG、"采集→开发→实时"是一条链，拆开反而多 3 次 RPC    |
| governance + catalog         | **governance**  | 治理产出直接消费于资产展示，同一 DB，同步调用不需要跨进程 |
| db-migration                 | —               | 嵌入 system-service（Sprint 0 只有 system 有 DB）         |

### 3.3 每个微服务的技术栈

所有微服务基础栈一致：

```
spring-boot-starter-web         # Spring Boot 4.0.7 Web（Tomcat）
spring-cloud-starter-alibaba-nacos-discovery   # 服务注册
spring-cloud-starter-alibaba-nacos-config      # 配置中心
spring-cloud-starter-openfeign                 # 服务间 HTTP 调用
spring-cloud-starter-loadbalancer              # 客户端负载均衡
spring-boot-starter-actuator                   # 健康检查 + Metrics
```

---

## 4. 服务间通信

### 4.1 通信方式选择

| 场景                                  | 方式                       | 理由                          |
|---------------------------------------|----------------------------|-------------------------------|
| **同步查询**（如治理服务查询元数据）  | OpenFeign (HTTP)           | 简单，Nacos 自动负载均衡      |
| **异步事件**（如血缘事件上报）        | Spring Cloud Stream (内部) | 解耦，后续可换 Kafka/RocketMQ |
| **大数据量传输**（如 Doris 查询结果） | Doris JDBC 直连            | 不走 HTTP 中转，避免 OOM      |
| **实时推送**（如 WebSocket 数据推送） | WebSocket 直连             | 长连接，不走 Feign            |

### 4.2 服务调用示例

```java
// engineering-service 调用 governance-service 上报血缘
@FeignClient(name = "governance-service", path = "/api/governance")
public interface GovernanceClient {
    @PostMapping("/lineage/report")
    Result<Void> reportLineage(@RequestBody LineageEvent event);
}
```

> engineering-service 内部（数据源管理→DAG 编排→CDC 管道）是 **同进程调用**，不经过 Feign。

### 4.3 调用链安全

- 所有外部请求 → Gateway 鉴权（JWT Token）
- 服务间调用 → Internal Token（Feign 拦截器自动注入）
- 敏感操作审计 → 通过 Feign 拦截器自动记录调用链

---

## 5. Nacos 配置中心设计

### 5.1 配置分层策略

```
Nacos Namespace: datanest-dev
│
├── shared-configs (共享配置组)
│   ├── shared-datasource.yaml       # 数据源公共配置
│   ├── shared-security.yaml         # JWT、加密密钥
│   ├── shared-auth.yaml             # 预置角色/权限矩阵 🆕
│   ├── shared-doris.yaml            # Doris 连接信息
│   ├── shared-iceberg.yaml          # Iceberg Catalog 配置
│   └── shared-flink.yaml            # Flink 配置
│
├── gateway-service.yaml             # 网关服务专属配置
├── system-service.yaml              # 用户权限服务 🆕
├── engineering-service.yaml         # 数据工程服务
├── governance-service.yaml          # 数据治理服务
└── data-service.yaml                # 数据服务
```

### 5.2 application.yml 配置（每个服务统一格式）

```yaml
spring:
  application:
    name: engineering-service
  config:
    import:
      - nacos:engineering-service.yaml?refreshEnabled=true
      - nacos:shared-datasource.yaml?refreshEnabled=true&group=shared-configs
      - nacos:shared-security.yaml?refreshEnabled=true&group=shared-configs
      - nacos:shared-doris.yaml?refreshEnabled=true&group=shared-configs
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_ADDR:localhost:8848}
        namespace: datanest-dev
      config:
        server-addr: ${NACOS_ADDR:localhost:8848}
        namespace: datanest-dev
        file-extension: yaml
```

### 5.3 配置热更新

- `refreshEnabled=true` 的服务配置支持 `@RefreshScope` 动态刷新
- 数据库密码、Doris FE 地址等关键配置修改后实时生效，无需重启
- 非 `refreshEnabled` 的静态配置（如日志级别）需重启

---

## 6. 核心数据流

### 6.1 离线 ETL 数据流

```
┌──────────┐                   ┌────────────────────┐
│ MySQL DB │── JDBC 批量读 ──▶│ engineering-service │
│ (Source) │                   │  (批量同步引擎)       │
└──────────┘                   └─────────┬──────────┘
                                         │ Stream Load
                              ┌──────────▼──────────┐
                              │    Apache Doris      │
                              │  (OLAP 存储引擎)      │
                              └──────────┬──────────┘
                                         │ JDBC Query
                              ┌──────────▼──────────┐
                              │ engineering-service  │
                              │  (SQL 任务执行)      │
                              └──────────┬──────────┘
                                         │ Feign: LineageEvent
                              ┌──────────▼──────────┐
                              │ governance-service   │
                              │  (血缘自动生成)       │
                              └─────────────────────┘
```

### 6.2 实时 CDC 数据流（v2.1 更新：增加 Iceberg 湖仓层）

```
┌──────────┐  Binlog                    ┌──────────────────────┐
│ MySQL DB │──────────────────────────▶│ engineering-service  │
│ (Source) │  Flink CDC Connector       │  Flink MiniCluster    │
└──────────┘                            │  CDC → Stream SQL    │
                                        └──────────┬───────────┘
                                                   │
                              ┌────────────────────┼───────────────────┐
                              │                    │                   │
                   ┌──────────▼──────┐  ┌──────────▼──────┐           │
                   │  Iceberg Table  │  │  Doris (实时查询) │           │
                   │  (湖仓主存储)    │  │  Iceberg Catalog  │           │
                   │  Data: MinIO    │  │  外部表查询       │           │
                   └────────┬───────┘  └──────────┬───────┘           │
                            │                     │                    │
                            │        ┌────────────┘                   │
                            │        │                                │
              ┌─────────────▼────────▼──────┐                         │
              │       data-service          │                         │
              │  WebSocket Push / API       │                         │
              └────────────────────────────┘                         │
```

**关键路径**：

1. Flink CDC 消费 MySQL Binlog → 写入 **Iceberg 表**（湖仓主存储，数据文件落在 MinIO）
2. Doris 通过 **Iceberg Catalog** 创建外部表，实时查询 Iceberg 数据
3. Iceberg 作为单一事实来源（Single Source of Truth），Doris 作为查询加速层

### 6.3 服务启动注册流程

```
1. Docker Compose 启动
   ├── Nacos Server 3.1.1 (先启动)
   ├── PostgreSQL 16           ← 元数据 + Iceberg JDBC Catalog
   └── Doris FE + BE
2. 各微服务依次启动
   ├── 从 Nacos Config 拉取 shared-configs + 专属配置
   ├── 向 Nacos Discovery 注册（服务名 + IP:Port）
   └── 健康检查就绪后标记为 UP
3. gateway-service
   ├── 从 Nacos 获取所有服务实例列表
   ├── 配置路由规则（/api/engineering/** → engineering-service）
   └── 对外暴露统一 8080 端口
```

---

## 7. 各微服务详细设计

### 7.1 gateway-service（API 网关）

| 维度             | 设计                                                                                     |
|------------------|------------------------------------------------------------------------------------------|
| **职责**         | 统一入口、JWT 鉴权、路由转发、限流                                                       |
| **路由规则**     | `/api/system/**` → `system-service`，`/api/engineering/**` → `engineering-service`，类推 |
| **鉴权**         | JWT Token Filter，白名单：`/api/auth/login`、`/actuator/health`                          |
| **限流**         | Sentinel 2.0.0（SCA 2025.1.0.0 内置），基于服务 + 接口粒度的 QPS 限制                    |
| **前端静态资源** | React 打包后由 gateway-service 托管（或独立 Nginx，生产环境建议 Nginx）                  |

### 7.2 system-service（用户与权限管理） 🆕

| 维度            | 设计                                                                                                                   |
|-----------------|------------------------------------------------------------------------------------------------------------------------|
| **职责**        | 用户 CRUD、角色 CRUD、4 预置角色 RBAC、登录凭据验证                                                                    |
| **用户存储**    | PostgreSQL 16，BCrypt 密码哈希                                                                                         |
| **Flyway 迁移** | 嵌入 system-service，启动自动执行 V1.0.0（建表）+ V1.0.1（种子数据）                                                   |
| **对外接口**    | `POST /users/verify`（Gateway Feign 调用验证登录）、`GET/POST /users`、`PUT /users/{id}/status`、`PUT /users/password` |
| **数据库操作**  | MyBatis-Plus 3.5.x（雪花主键）                                                                                         |

### 7.3 engineering-service（数据工程）

| 维度           | 设计                                                                        |
|----------------|-----------------------------------------------------------------------------|
| **职责**       | 数据源管理 + 批量同步 + DAG 编排 + 任务调度 + CDC 实时管道                  |
| **批量同步**   | 源端 JDBC 读 → 内存转换 → Doris Stream Load 写                              |
| **调度引擎**   | DolphinScheduler 3.4.2，DataNest 前端 ReactFlow 画布 → DolphinScheduler API |
| **CDC 实时**   | Flink CDC MySQL Connector（Binlog） → Doris Stream Load / Iceberg Sink      |
| **血缘钩子**   | 任务执行前后通过 Listener → Feign 调 governance-service 上报血缘            |
| **数据源类型** | MySQL、PostgreSQL、Doris（P0）；Oracle、SQL Server、MongoDB、Kafka（P1）    |

> 原 integration-service + dev-service + realtime-service 合并。同域内调用（如 DAG→数据源、CDC→同步任务）均为
> **进程内方法调用**，不经过 Feign。

### 7.4 governance-service（数据治理与资产）

| 维度           | 设计                                                              |
|----------------|-------------------------------------------------------------------|
| **职责**       | 元数据采集 + 血缘图谱 + 质量规则 + 数据标准 + 资产目录 + 数据搜索 |
| **元数据存储** | PostgreSQL 16（表结构 + JSONB 扩展属性）                          |
| **血缘存储**   | Neo4j 5.x Community，Cypher 图查询                                |
| **搜索**       | OpenSearch 2.x，模糊搜索 + 分词，响应 < 2s                        |
| **质量检查**   | 规则引擎：完整性、唯一性、范围校验、自定义 SQL                    |

> 原 governance-service + catalog-service 合并。资产详情从 Feign 跨服务调用变为同进程 DB 查询，搜索从异步同步变为进程内索引写入。

### 7.5 data-service（数据服务）

| 维度         | 设计                                                  |
|--------------|-------------------------------------------------------|
| **职责**     | SQL 查询终端、API 生成与管理                          |
| **SQL 终端** | Doris JDBC 直连，支持 SQL 执行、结果导出（CSV/Excel） |
| **API 生成** | 基于表结构自动生成 RESTful API，支持参数化查询和分页  |
| **实时推送** | 桥接 engineering-service 的 WebSocket，向业务系统推送 |

---

## 8. Doris 存储引擎集成

### 8.1 嵌入方式

DataNest 通过 Docker Compose 内嵌 Doris FE + BE：

```yaml
# docker-compose.yml 片段
doris-fe:
  image: apache/doris:4.1.3-fe-ubuntu
  ports:
    - "8030:8030"   # HTTP
    - "9030:9030"   # JDBC / MySQL Protocol

doris-be:
  image: apache/doris:4.1.3-be-ubuntu
  ports:
    - "9060:9060"   # BE heartbeat
```

### 8.2 DataNest 如何使用 Doris

| 场景         | 方式                                                                      |
|--------------|---------------------------------------------------------------------------|
| **建表**     | 通过 Doris JDBC 执行 DDL（在 engineering-service 同步任务中）             |
| **写入**     | Stream Load（批量/实时写入，HTTP PUT 协议）                               |
| **查询**     | JDBC（MySQL 协议兼容，标准 SQL）                                          |
| **治理集成** | 通过 Doris `information_schema` 采集元数据 + `show table status` 采集统计 |

### 8.3 Doris 建模策略

| 场景                               | 模型           | 理由               |
|------------------------------------|----------------|--------------------|
| **行为日志**（流水表、点击日志）   | Duplicate 模型 | 无需聚合，明细查询 |
| **聚合报表**（日报、周报）         | Aggregate 模型 | 预聚合，查询快     |
| **维度宽表**（用户画像、商品宽表） | Unique 模型    | 主键唯一，支持更新 |

### 8.4 为什么不继续自研列存引擎？

| 考量     | v1.0 自研      | v2.0 用 Doris                         |
|----------|----------------|---------------------------------------|
| 开发量   | 3 Sprint       | 0（集成配置）                         |
| 性能     | 需持续优化     | Apache 顶级项目，TPC-H 已验证         |
| SQL 兼容 | 需自研         | MySQL 协议原生兼容                    |
| 运维     | 需自己修复 Bug | 社区维护，版本升级                    |
| 治理集成 | 完全可把控     | 通过 information_schema + Metrics API |

> **决策**：放弃自研列存引擎，用 Doris 替代。收益是节省 3 个 Sprint，代价是治理集成深度略降（通过 API 而非引擎内部
> Hook），但这完全可接受。

---

## 8.5 Apache Iceberg 湖仓集成 🆕

### 8.5.1 架构定位

DataNest 的存储架构采用 **查询加速层 + 湖仓主存储** 分层设计：

```
┌─────────────────────────────────────────┐
│         查询加速层 (Serving Layer)       │
│         Apache Doris (OLAP 引擎)         │
│   ┌─────────────────────────────────┐   │
│   │  Iceberg Catalog 外部表          │   │
│   │  (Doris 直接查询 Iceberg 表)     │   │
│   └─────────────────────────────────┘   │
├─────────────────────────────────────────┤
│         湖仓层 (Lakehouse Layer)         │
│         Apache Iceberg (表格式)          │
│   ┌──────────────┐ ┌────────────────┐   │
│   │ JDBC Catalog │ │  MinIO / Local │   │
│   │ (PostgreSQL) │ │  (数据文件)     │   │
│   └──────────────┘ └────────────────┘   │
└─────────────────────────────────────────┘
```

- **Iceberg**：湖仓主存储，ACID 事务、Schema 演进、时间旅行、分区演进
- **Doris**：查询加速层，通过 Iceberg Catalog 直接查 Iceberg 表，提供秒级 OLAP 响应
- **Flink CDC**：数据写入层，消费 Binlog → 写入 Iceberg 表
- **MinIO**：S3 兼容对象存储，存放 Iceberg 数据文件和元数据文件

### 8.5.2 嵌入方式

```yaml
# docker-compose.yml
minio:
  image: minio/minio:RELEASE.2024
  ports:
    - "9000:9000"   # S3 API
    - "9001:9001"   # Console
  environment:
    MINIO_ROOT_USER: datanest
    MINIO_ROOT_PASSWORD: datanest123
  command: server /data --console-address ":9001"
```

Iceberg **不需要独立服务**——它是纯表格式规范，通过 JDBC Catalog 复用已有的 PostgreSQL 存储元数据：

```java
// engineering-service 中配置 Iceberg Catalog
@Configuration
public class IcebergConfig {
    @Bean
    public Catalog icebergCatalog() {
        return CatalogBuilder.build("jdbc")
            .withProperty("uri", "jdbc:postgresql://postgresql:5432/datanest")
            .withProperty("warehouse", "s3://datanest-warehouse/")
            .withProperty("s3.endpoint", "http://minio:9000")
            .withProperty("s3.access-key-id", "datanest")
            .withProperty("s3.secret-access-key", "datanest123")
            .build();
    }
}
```

### 8.5.3 Doris 查询 Iceberg 表

```sql
-- 在 Doris 中创建 Iceberg Catalog
CREATE CATALOG iceberg_catalog PROPERTIES (
    "type" = "iceberg",
    "iceberg.catalog.type" = "jdbc",
    "iceberg.catalog.jdbc.uri" = "jdbc:postgresql://postgresql:5432/datanest",
    "iceberg.catalog.warehouse" = "s3://datanest-warehouse/",
    "s3.endpoint" = "http://minio:9000",
    "s3.access_key" = "datanest",
    "s3.secret_key" = "datanest123"
);

-- 查询 Iceberg 表（对用户透明，和查 Doris 内部表一样）
SELECT * FROM iceberg_catalog.datanest.orders_realtime
WHERE dt = '2026-07-23';
```

### 8.5.4 为什么选 Iceberg？

| 考量           | Iceberg                         | Hudi                             | Delta Lake      |
|----------------|---------------------------------|----------------------------------|-----------------|
| Flink CDC 写入 | Flink Iceberg Sink 原生支持     | Hudi DeltaStreamer 最强 CDC 场景 | 主要面向 Spark  |
| Doris 集成     | Doris 2.1+ 原生 Iceberg Catalog | 需要 Hudi 外表适配               | 不支持          |
| Schema 演进    | 原生支持，安全加列/改列         | 支持但限制较多                   | 支持            |
| 时间旅行       | 原生 `AS OF TIMESTAMP`          | 支持                             | 支持            |
| 生态中立       | Apache 顶级项目，多云兼容       | Apache 顶级项目                  | Databricks 主导 |

> **决策**：Iceberg 在 Flink + Doris 组合下集成最顺畅，生态最中立。

---

## 9. Flink CDC 实时计算集成

### 9.1 嵌入方式

```java
// engineering-service 启动时创建内嵌 Flink MiniCluster
@Configuration
public class FlinkMiniClusterConfig {
    
    @Bean(destroyMethod = "close")
    public MiniCluster miniCluster() {
        Configuration config = new Configuration();
        config.setInteger(TaskManagerOptions.NUM_TASK_SLOTS, 4);
        config.setInteger(RestOptions.PORT, 0); // 随机端口
        config.setString(CheckpointingOptions.CHECKPOINTS_DIRECTORY, 
            "/data/datanest/flink-checkpoints");
        
        MiniCluster cluster = new MiniCluster(config);
        cluster.start();
        return cluster;
    }
}
```

### 9.2 CDC Pipeline 定义

```java
// 在 engineering-service 中定义 CDC 任务
StreamExecutionEnvironment env = 
    StreamExecutionEnvironment.getExecutionEnvironment();
env.enableCheckpointing(60000); // 60s checkpoint

// MySQL CDC Source
MySqlSource<String> source = MySqlSource.<String>builder()
    .hostname("source-mysql")
    .port(3306)
    .databaseList("business_db")
    .tableList("business_db.orders")
    .username("root")
    .password("password")
    .deserializer(new JsonDebeziumDeserializationSchema())
    .build();

// Transform (Stream SQL via Calcite)
DataStream<String> stream = env
    .fromSource(source, WatermarkStrategy.noWatermarks(), "MySQL CDC")
    .map(new OrderTransform());

// Sink to Iceberg (v2.1: 写入湖仓主存储)
stream.sinkTo(FlinkSink.forRowData(input)
    .tableLoader(icebergTableLoader)
    .upsert(true)
    .build());

env.execute("CDC: orders → iceberg.datanest.orders");
```

### 9.3 Flink CDC vs v1.0 自研

| 考量          | v1.0 自研             | v2.0 Flink CDC                         |
|---------------|-----------------------|----------------------------------------|
| 开发量        | 3 Sprint              | 0.5 Sprint（集成 + 管理界面）          |
| Exactly-Once  | 需自研 Chandy-Lamport | Flink Checkpoint 原生支持              |
| CDC Connector | 只支持 MySQL          | Flink CDC 支持 MySQL/PG/Oracle/MongoDB |
| SQL 流处理    | 需自研                | Flink SQL 原生支持 TUMBLE/HOP/SESSION  |
| 治理集成      | 完全可把控            | 通过 Flink Metrics + JobListener       |

> **决策**：放弃自研流处理引擎，用 Flink MiniCluster 内嵌。收益是节省 2.5 个 Sprint 且获得完整 Exactly-Once + CDC 生态。

---

## 10. 部署拓扑

### 10.1 MVP 单机部署（Docker Compose）

```
┌──────────────────────────────────────────────────────────────┐
│                      Docker Compose                           │
│                                                               │
│  ┌────────────────┐  ┌─────────────────┐  ┌──────────────┐  │
│  │ Nacos 3.1.1    │  │ PostgreSQL 16   │  │ OpenSearch    │  │
│  │ Registry+Config│  │ (Metadata Store) │  │ 2.x (Search) │  │
│  │ Port: 8848     │  │ Port: 5432      │  │ Port: 9200   │  │
│  └───────┬────────┘  └────────┬────────┘  └──────┬───────┘  │
│          │                    │                    │          │
│  ┌───────┴────────────────────┴────────────────────┴───────┐  │
│  │                     DataNest Services                     │  │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐   │  │
│  │  │ Gateway  │ │Integration│ │   Dev    │ │Governance│   │  │
│  │  │  :8080   │ │  :8081   │ │  :8082   │ │  :8083   │   │  │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘   │  │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐                 │  │
│  │  │ Catalog  │ │  Data    │ │ Realtime │                 │  │
│  │  │  :8084   │ │  :8085   │ │  :8086   │                 │  │
│  │  └──────────┘ └──────────┘ └──────────┘                 │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                               │
│  ┌──────────────────────────┐  ┌───────────────────────────┐  │
│  │     Apache Doris          │  │   Flink MiniCluster       │  │
│  │  FE:9030  BE:9060        │  │   (内嵌在 realtime 中)     │  │
│  │  Iceberg Catalog 外部表   │  │   CDC → Iceberg Sink      │  │
│  └──────────────────────────┘  └───────────────────────────┘  │
│                                                               │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │   Iceberg Lakehouse Layer                                 │ │
│  │  ┌─────────────────┐  ┌─────────────────────────────┐   │ │
│  │  │ Iceberg Catalog │  │  MinIO (S3 兼容存储)          │   │ │
│  │  │ (JDBC→PG)      │  │  Port: 9000 (API) / 9001    │   │ │
│  │  └─────────────────┘  └─────────────────────────────┘   │ │
│  └──────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

### 10.2 启动顺序

```
1. Nacos Server            ← 注册中心，最先启动
2. PostgreSQL 16           ← 元数据 + Iceberg JDBC Catalog
3. MinIO                   ← Iceberg 数据文件存储 🆕
4. OpenSearch 2.x          ← 搜索索引
5. Doris FE + BE           ← OLAP 查询引擎
6. 各微服务（按依赖顺序）
   ├── system-service       (依赖 PG)
   ├── engineering-service  (依赖 PG + Doris)
   ├── governance-service   (依赖 PG + Neo4j + OpenSearch)
   ├── data-service         (依赖 Doris)
   └── gateway-service      (最后起，对外暴露端口)
```

---

## 11. 关键架构决策（ADR）

### ADR-001: 微服务 vs 模块化单体（修订）

| 项目               | 内容                                                                                                                                  |
|--------------------|---------------------------------------------------------------------------------------------------------------------------------------|
| **状态**           | Accepted（v2.0 修订）                                                                                                                 |
| **原决策（v1.0）** | 模块化单体                                                                                                                            |
| **新决策（v2.0）** | **微服务架构**，Nacos 3.1.1 注册发现 + 配置中心                                                                                       |
| **修订原因**       | 使用 Doris + Flink CDC 替代自建引擎后，开发量降低，可投入工程资源到微服务拆分                                                         |
| **后果**           | 📈 服务独立部署/伸缩/升级；📈 v2.3 合并为 5 服务减少运维负担；📉 运维复杂度增加（5 个服务 + Nacos + PG + Doris + Neo4j + OpenSearch） |

### ADR-002: OLAP 存储 —— Doris vs 自研列存（修订）

| 项目               | 内容                                                                                                          |
|--------------------|---------------------------------------------------------------------------------------------------------------|
| **状态**           | Accepted（v2.0 修订）                                                                                         |
| **原决策（v1.0）** | 自研列存引擎（借鉴 Parquet/Iceberg + Calcite）                                                                |
| **新决策（v2.0）** | **Apache Doris 内嵌**，Docker Compose 集成 FE + BE                                                            |
| **修订原因**       | Doris 是 Apache 顶级 OLAP 项目，MySQL 协议原生兼容，向量化执行 + MPP 架构成熟，开发量为 0                     |
| **后果**           | 📈 节省 3 Sprint，生产级 OLAP 能力；📉 治理集成通过 information_schema 和 Metrics API，不如引擎内部 Hook 深入 |

### ADR-003: 流处理 —— Flink CDC vs 自研（修订）

| 项目               | 内容                                                                                                            |
|--------------------|-----------------------------------------------------------------------------------------------------------------|
| **状态**           | Accepted（v2.0 修订）                                                                                           |
| **原决策（v1.0）** | 自研 Stateful Stream Processor                                                                                  |
| **新决策（v2.0）** | **Flink MiniCluster 内嵌**，Flink CDC Connector + Stream SQL                                                    |
| **修订原因**       | Flink CDC 成熟，自带 Exactly-Once + Checkpoint + 多数据源 CDC；Flink SQL 原生支持 TUMBLE/HOP/SESSION 窗口       |
| **后果**           | 📈 节省 2.5 Sprint，完整 CDC 生态；📉 Flink MiniCluster 非生产级 HA，生产环境需升级为 Flink Standalone/K8s 部署 |

### ADR-004: Nacos 作为注册中心 + 配置中心

| 项目       | 内容                                                                           |
|------------|--------------------------------------------------------------------------------|
| **状态**   | Accepted                                                                       |
| **上下文** | 微服务架构需要服务发现 + 统一配置管理                                          |
| **决策**   | **Nacos 3.1.1** 同时承担注册中心和配置中心，不需额外部署 Consul/Apollo         |
| **后果**   | 📈 单一组件满足两个需求，阿里生态一致性好；📉 Nacos 3.x 较新，社区踩坑文档较少 |

### ADR-005: Nacos 配置按粒度切分共享配置

| 项目       | 内容                                                                                                                           |
|------------|--------------------------------------------------------------------------------------------------------------------------------|
| **状态**   | Accepted                                                                                                                       |
| **上下文** | 多服务共享数据源连接、JWT 密钥、Doris FE 地址等配置                                                                            |
| **决策**   | **shared-configs group** 存放所有服务公共配置，每个服务通过 `spring.config.import` 按需引入。专属配置放在服务同名 Data ID 下。 |
| **粒度**   | `shared-datasource.yaml`、`shared-security.yaml`、`shared-doris.yaml`、`shared-flink.yaml` 四个共享配置                        |
| **后果**   | 📈 修改公共配置一次生效所有服务；📉 共享配置变更影响面大，需要严格的变更流程                                                   |

### ADR-006: 湖仓表格式 —— Iceberg vs Hudi vs Delta Lake 🆕

| 项目         | 内容                                                                                    |
|--------------|-----------------------------------------------------------------------------------------|
| **状态**     | Accepted                                                                                |
| **上下文**   | 需要湖仓表格式提供 ACID 事务、Schema 演进、时间旅行，替代原 v1.0 自研湖仓引擎           |
| **决策**     | **Apache Iceberg**，JDBC Catalog 复用 PostgreSQL，数据文件存储 MinIO                    |
| **替代方案** | Hudi（CDC 场景强但 Doris 集成弱）；Delta Lake（Spark 生态强但 Flink/Doris 集成弱）      |
| **后果**     | 📈 Flink + Doris 原生集成最顺畅，生态中立；📉 需额外部署 MinIO（或使用本地 FS 兼容 S3） |

### ADR-007: 调度引擎 —— DolphinScheduler vs 自研 🆕

| 项目       | 内容                                                                                                                 |
|------------|----------------------------------------------------------------------------------------------------------------------|
| **状态**   | Accepted                                                                                                             |
| **上下文** | 需要 DAG 可视化编排 + 分布式调度，原 v1.0 为自研轻量调度器                                                           |
| **决策**   | **DolphinScheduler 3.4.2**，DataNest 前端 ReactFlow 自建 DAG 画布，通过 DolphinScheduler API 提交和管理任务          |
| **后果**   | 📈 生产级调度能力（千万级任务），DAG 编排成熟；📉 需部署 DolphinScheduler Master + Worker + API 服务，增加运维复杂度 |

### ADR-008: 血缘存储 —— Neo4j vs PostgreSQL CTE 🆕

| 项目       | 内容                                                                            |
|------------|---------------------------------------------------------------------------------|
| **状态**   | Accepted                                                                        |
| **上下文** | 血缘关系是典型的图结构（表→字段→任务→应用），需要高效的多跳遍历和影响分析       |
| **决策**   | **Neo4j 5.x Community**，Cypher 图查询语言                                      |
| **后果**   | 📈 图遍历性能远超 PostgreSQL 递归 CTE，支持复杂图算法；📉 需额外部署 Neo4j 服务 |

### ADR-009: 鉴权 —— Sa-Token + Redis vs Spring Security 🆕

| 项目       | 内容                                                                           |
|------------|--------------------------------------------------------------------------------|
| **状态**   | Accepted                                                                       |
| **上下文** | 微服务架构需要统一鉴权，支持角色权限、Token 续期、踢人下线                     |
| **决策**   | **Sa-Token 1.40+ + Redis**，注解鉴权（`@SaCheckRole`），Redis 存会话实现分布式 |
| **后果**   | 📈 比 Spring Security 轻量 10 倍，配置极简；📉 社区生态小于 Spring Security    |

### ADR-010: ORM + 迁移 —— MyBatis-Plus + Flyway 🆕

| 项目       | 内容                                                                                                             |
|------------|------------------------------------------------------------------------------------------------------------------|
| **状态**   | Accepted                                                                                                         |
| **上下文** | 需要 ORM 框架 + 数据库版本管理 + 分布式主键                                                                      |
| **决策**   | **MyBatis-Plus 3.5.x**（ORM + 雪花主键自动生成）+ **Flyway 10.x**（嵌入 system-service，服务启动时自动执行迁移） |
| **后果**   | 📈 雪花主键全局唯一免碰撞；📈 迁移与使用服务的版本强绑定，不会出现不一致                                         |

### ADR-011: 微服务合并——8 → 5 🆕

| 项目       | 内容                                                                                                                                                                                                            |
|------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **状态**   | Accepted（v2.3）                                                                                                                                                                                                |
| **上下文** | v2.0 拆了 8 个微服务。分析发现三大问题：①integration-governance-catalog 共享 PG，"采集→治理→展示"硬拆成 3 次 RPC；②system / data-service 代码量 < 500 行不配独立进程；③开源项目初期 1-3 人，人均 3-4 服务不现实 |
| **决策**   | 按业务域内聚合并为 **5 个微服务**：gateway（WebFlux 不可合）、system（用户权限）、engineering（integration+dev+realtime）、governance（governance+catalog）、data-service                                       |
| **后果**   | 📈 认知负担大幅降低，同域调用变进程内方法调用；📉 engineering 和 governance 单服务体量较大，需包结构分层防耦合                                                                                                  |

---

## 12. 代码仓库结构

```
data-nest/
├── pom.xml                            # Root POM（版本管理 + 模块聚合）
├── docker-compose.yml
│
├── data-nest-common/                  # 公共模块（所有服务依赖）
│   └── src/main/java/com/datanest/common/
│       ├── model/                     # TableRef, ColumnRef, DataType, Result<T>
│       ├── event/                     # LineageEvent, MetadataChangeEvent
│       ├── exception/                 # 全局异常定义
│       └── util/                      # SqlUtils, RetryUtils
│
├── data-nest-gateway/                 # 网关服务（Sa-Token JWT + 路由）
│   └── src/main/java/com/datanest/gateway/
│       ├── GatewayApplication.java
│       ├── config/                    # RouteConfig, CorsConfig
│       └── filter/                    # JwtAuthFilter
│
├── data-nest-system/                  # 用户与权限管理
│   └── src/main/java/com/datanest/system/
│       ├── SystemApplication.java
│       ├── controller/               # UserController, RoleController
│       ├── service/                  # UserService, RoleService
│       ├── mapper/                   # MyBatis-Plus Mapper
│       └── resources/db/migration/    # Flyway 迁移脚本
│
├── data-nest-engineering/             # 数据工程（数据源 + 开发 + 实时）
│   └── src/main/java/com/datanest/engineering/
│       ├── EngineeringApplication.java
│       ├── datasource/               # 数据源连接管理
│       ├── sync/                     # 批量同步引擎
│       ├── dag/                      # DAG 编排
│       ├── scheduler/                # 调度引擎（DolphinScheduler）
│       └── cdc/                      # Flink CDC 管理
│
├── data-nest-governance/              # 数据治理（元数据 + 血缘 + 质量 + 资产）
│   └── src/main/java/com/datanest/governance/
│       ├── GovernanceApplication.java
│       ├── metadata/                 # 元数据采集
│       ├── lineage/                  # 血缘图谱（Neo4j）
│       ├── quality/                  # 质量规则
│       ├── catalog/                  # 资产目录 + 搜索
│       └── standard/                 # 数据标准
│
├── data-nest-service/                 # 数据服务
│   └── src/main/java/com/datanest/service/
│       ├── DataServiceApplication.java
│       ├── query/                    # SQL 终端
│       └── api/                      # API 生成
│
└── data-nest-frontend/                # 前端工程（React 18 + Vite）
    ├── package.json
    ├── vite.config.ts
    └── src/
        ├── pages/                     # 各模块页面
        │   ├── engineering/           # 数据工程
        │   ├── governance/            # 数据治理
        │   ├── service/               # 数据服务
        │   └── system/                # 系统管理
        ├── components/                # 公共组件
        └── api/                       # 后端 API 调用封装
```

### 12.1 Maven 多模块配置

```xml
<!-- Root pom.xml -->
<groupId>com.datanest</groupId>
<artifactId>data-nest</artifactId>
<version>1.0.0-SNAPSHOT</version>
<packaging>pom</packaging>

<modules>
    <module>data-nest-common</module>
    <module>data-nest-gateway</module>
    <module>data-nest-system</module>
    <module>data-nest-engineering</module>
    <module>data-nest-governance</module>
    <module>data-nest-service</module>
</modules>
```

---

## 13. 演进策略

### 13.1 Sprint 大幅缩短

| 原 Sprint (v1.0 自研)                      | 内容            | v2.0 调整                                     |
|--------------------------------------------|-----------------|-----------------------------------------------|
| Sprint 3-5: 存储引擎 MVP（3 Sprint）       | 自研列存 + 湖仓 | **删除**，替换为 Doris 集成配置（Sprint 0）   |
| Sprint 13-15: 实时计算引擎 MVP（3 Sprint） | 自研流处理      | **删除**，替换为 Flink CDC 集成（0.5 Sprint） |
| Sprint 18: 性能调优                        | 自研引擎优化    | 可合并到集成测试 Sprint                       |

**预估从 19 Sprint 压缩到 12-13 Sprint**。

### 13.2 生产环境升级路径

| 组件           | MVP(内嵌)                    | 生产环境                             |
|----------------|------------------------------|--------------------------------------|
| **Doris**      | Docker Compose 单 FE + 单 BE | 3FE + 3BE 集群，独立部署             |
| **Flink**      | MiniCluster 内嵌             | Flink Standalone / K8s Operator 集群 |
| **Nacos**      | 单节点                       | 3 节点集群（Raft 协议）              |
| **OpenSearch** | 嵌入式单节点                 | 3 节点集群                           |

### 13.3 技术债务清单

| 债务                        | 影响                       | 偿还时机                                                        |
|-----------------------------|----------------------------|-----------------------------------------------------------------|
| Flink MiniCluster 非生产 HA | 故障时流处理中断           | 生产化阶段升级为 Standalone                                     |
| Nacos 3.x 社区踩坑文档少    | 排查问题可能耗时           | 跟随社区生态成熟                                                |
| 微服务调试复杂度            | 本地需起 5 个服务 + 中间件 | 提供 Docker Compose 开发环境；engineering/governance 内部包分层 |

---

## 附录

### A. 版本依赖速查表

| 组件                 | 版本       | GroupId                   | ArtifactId                              |
|----------------------|------------|---------------------------|-----------------------------------------|
| Spring Boot          | 4.0.7      | org.springframework.boot  | spring-boot-starter-parent              |
| Spring Cloud         | 2025.1.2   | org.springframework.cloud | spring-cloud-dependencies               |
| Spring Cloud Alibaba | 2025.1.0.0 | com.alibaba.cloud         | spring-cloud-alibaba-dependencies       |
| Nacos Server         | 3.1.1      | —                         | Docker: nacos/nacos-server:3.1.1        |
| Apache Doris         | 4.1.3      | —                         | Docker: apache/doris:4.1.3-fe/be-ubuntu |
| Flink CDC            | 3.2.x      | org.apache.flink          | flink-connector-mysql-cdc               |
| PostgreSQL           | 16         | —                         | Docker: postgres:16-alpine              |
| OpenSearch           | 2.x        | —                         | Docker: opensearchproject/opensearch:2  |
| JDK                  | 21 LTS     | —                         | —                                       |
| Maven                | 3.9+       | —                         | —                                       |

### B. 文档修订记录

| 版本   | 日期       | 修订内容                                                                                                                | 作者       |
|--------|------------|-------------------------------------------------------------------------------------------------------------------------|------------|
| v1.0   | 2026-07-23 | 初始版本，模块化单体 + 自研引擎                                                                                         | 软件架构师 |
| v2.0   | 2026-07-23 | 完全重写：微服务 + Nacos + Doris + Flink CDC；5 个 ADR 修订                                                             | 软件架构师 |
| v2.1   | 2026-07-23 | 新增 Apache Iceberg 湖仓层；新增 MinIO 对象存储；ADR-006                                                                | 软件架构师 |
| v2.2   | 2026-07-23 | DolphinScheduler 3.4.2 调度；Neo4j 血缘；Addax 6.0.11 同步；Sa-Token+Redis 鉴权；MyBatis-Plus+Flyway；PG16；ADR-007~010 | 软件架构师 |
| v2.3   | 2026-07-23 | 微服务 8→5 合并；新增 system-service；Flyway 嵌入；ADR-011                                                              | 软件架构师 |
| v2.3.1 | 2026-07-23 | 版本统一：PG 15→16、Doris 2.1→4.1.3、system 端口 8083→8087                                                              | 软件架构师 |