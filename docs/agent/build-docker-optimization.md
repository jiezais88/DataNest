# 后端打包与 Docker 优化分析报告

> 状态：**P0 + P1 + P4 已落地**（2026-08-05），P2/P3 暂缓
> 日期：2026-08-05
> 范围：仅针对后端 Maven 打包 + 各服务 Dockerfile/docker-compose 构建链路

## 0. 本次已落地变更（P0 + P1 + P4）

| 项 | 内容 |
|----|------|
| **P0** `.dockerignore` | 根目录新增 `data-nest/.dockerignore`，排除各模块 `target/`（仅放行 `*.jar`）、`.git/`、前端目录、`node_modules/`、`data/`、`scripts/`、`shared-configs/` 等，context 从数百 MB 降到几 MB |
| **P1** 分层构建 | 根 `pom.xml` `pluginManagement` 的 `spring-boot-maven-plugin` 加 `<layers><enabled>true</enabled></layers>`；6 个 Dockerfile 改为两阶段：`layertools extract` 解层 + 分层 `COPY --from=builder` |
| **P4** 抽公共 base | 6 个服务 Dockerfile 从各模块目录迁至 `data-nest/docker/*.Dockerfile` 集中管理，统一「基础镜像 + apk + 分层 COPY」结构，公共层（`FROM eclipse-temurin:21-jre-alpine` + 相同 apk RUN）由 Docker 全局共享层缓存 |

### 构建/部署方式变化

- `docker-compose.yml` 中 6 个后端服务的 `build.context` 由各模块目录改为**根目录 `.`**，`dockerfile` 指向 `docker/<service>.Dockerfile`。
- 旧的各模块 `Dockerfile` 已删除；`data-nest-engineering/docker-entrypoint.sh` 保留，启动命令由 `exec java -jar /app/app.jar` 改为 `exec java org.springframework.boot.loader.launch.JarLauncher`（分层应用启动方式）。
- 命令不变：`mvn clean package -DskipTests` 后 `docker compose build app-xxx`。

### 分层构建后需注意

- 解层后镜像内无 `app.jar` 单文件，启动统一用 `org.springframework.boot.loader.launch.JarLauncher`（Spring Boot 4 官方分层运行方式）。
- 首次构建会重新解层（多一步 `extract`），之后改代码时 `dependencies/spring-boot-loader/snapshot-dependencies` 三层命中缓存，仅 `application` 层重建。

## 1. 现状盘点

### 1.1 Maven 打包现状

- 6 个可运行服务（gateway / system / engineering / governance / worker / job）均已配置 `spring-boot-maven-plugin` 的 `repackage`，产出 **fat jar**（BOOT-INF 内嵌全部依赖）。
- 5 个库模块（common / task-core-entity / alert / task-core-governance / task-core）只产普通 jar，供服务模块依赖。
- 根 `pom.xml` 未开启**并行构建**（无 `-T` 配置），未配置 maven 镜像加速，未配置 `spring-boot-maven-plugin` 的 **layers 分层**。

### 1.2 各 fat jar 实测大小（决定优化收益的关键数据）

| 服务 | fat jar 大小 |
|------|------------|
| app-gateway | 77.1 MB |
| app-system | 65.9 MB |
| app-engineering | 79.2 MB |
| app-governance | 88.0 MB |
| app-job | 88.1 MB |
| app-worker | 88.0 MB |

### 1.3 Dockerfile 现状

- 全部基于 `eclipse-temurin:21-jre-alpine`，**单阶段**构建，从宿主机 `target/*.jar` `COPY` 进镜像（依赖宿主机已先 `mvn package`）。
- engineering 额外装 `python3+pandas+pymysql`；worker 用多阶段从 `wgzhao/addax` 拷贝 Addax 二进制。
- **没有任何 `.dockerignore`**，docker build context 会携带每个模块的 `target/`、`.git/`、`node_modules`（前端目录）、临时文件等。
- 镜像**以 root 运行**，未创建非 root 用户（安全基线缺失）。
- healthcheck 依赖 `nc`（netcat-openbsd），gateway/system/governance/job 四个 Dockerfile 高度重复。

## 2. 问题与收益量化

### P0 — 缺少 `.dockerignore`（问题最普遍、改动最小、收益最直接）

docker build 会把 context（Dockerfile 所在目录）整体打包上传给 daemon。当前各服务目录 context 会带上：

- `target/`（fat jar 66~88 MB + `.class` 等），多个模块累计数百 MB；
- `node_modules/`（若 context 上溯到含前端的父目录）；
- `.git/`、IDE 配置、日志等。

**影响**：每次 `docker compose build` 都要先序列化、传输这些大目录，本地也慢，CI 更明显。

**修复**：每个服务目录加一份 `.dockerignore`，至少排除 `target/`、`.git/`、`*.iml`、`.idea/`、日志。改动极小、零风险。

### P1 — 未开启 spring-boot 分层构建（layers），迭代时依赖层无法缓存

fat jar 默认被打成**一个不可分割的大文件**。`COPY target/app.jar` 这一层，只要 jar 内容有任何变化（哪怕改一行代码），这一层就完全失效，buildkit 要重新 COPY 整个 66~88 MB jar 并产生新的镜像层。

开启 `spring-boot-maven-plugin` 的 `<layers>` 后，fat jar 会拆为：**dependencies（依赖，几乎不变）/ spring-boot-loader / snapshot-dependencies / application（应用代码，常变）** 四层。配合 buildkit 多阶段：

```dockerfile
FROM eclipse-temurin:21-jre-alpine AS extractor
COPY app.jar /app.jar
RUN java -Djarmode=layertools -jar /app.jar extract

FROM eclipse-temurin:21-jre-alpine
COPY --from=extractor /app/dependencies/ ./
COPY --from=extractor /app/spring-boot-loader/ ./
COPY --from=extractor /app/snapshot-dependencies/ ./
COPY --from=extractor /app/application/ ./
```

**收益**：改代码时，前三层（依赖，合计约 60~85 MB）命中原镜像缓存，**只需重建 application 层（通常几百 KB）**，镜像构建时间从「重新 COPY 整个大 jar」降到「COPY 应用代码层」。

**代价**：需在根 pom 或各服务 pom 给 `spring-boot-maven-plugin` 加 `<layers><enabled>true</enabled></layers>`，并改 6 个 Dockerfile 为多阶段分层 COPY。由于服务仍需宿主机先 package，建议保留「宿主机 package → COPY target jar → 容器内 extract」流程，不引入 `mvn` 构建阶段（见 P2）。

### P2 — 单阶段 + 直接 COPY 宿主机 jar，构建链路割裂

当前 Dockerfile 强依赖「宿主机已经 `mvn package` 出 target jar」，镜像本身不负责编译。这带来：

- 构建不可复现（换机器/CI 上没有 jar 就直接失败）；
- 链条割裂：`mvn package` 和 `docker compose build` 是两步手动操作，容易漏跑导致旧 jar 进镜像（AGENTS.md 已记录"buildkit 缓存未更新"踩坑，正是这个根源）。

**可选修复**：改为 Maven 多阶段，把 `mvn package` 放进构建：

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY data-nest-common ./data-nest-common
# ...（按模块依赖顺序逐个 COPY）
RUN mvn -pl <module> -am package -DskipTests
FROM eclipse-temurin:21-jre-alpine
COPY --from=build /build/<module>/target/*.jar app.jar
```

**但要注意代价**：多模块 + `mvn` 构建阶段会让**首次和全量构建明显变慢**（每次都要完整 mvn package），对本地频繁迭代未必划算。建议**保持现有「宿主机先 package」的本地开发流程**，把 Maven 多阶段作为可选的 CI/发布流程（或配合 P1 分层缓存后的 buildkit 缓存使用）。

### P3 — 镜像以 root 运行（安全）

所有服务镜像默认以 root 启动。生产安全基线建议非 root：

```dockerfile
RUN addgroup -S app && adduser -S app -G app
USER app
```

注意：worker 镜像需要写 `/opt/addax` 日志/任务文件，engineering 需要跑 python sandbox，切非 root 前要确认这些路径的写权限，**需逐服务验证**，故降级为 P3（低风险高收益但需回归）。

### P4 — Dockerfile 重复 + healthcheck 依赖 nc

- gateway/system/governance/job 四个 Dockerfile 几乎逐字相同，可抽取公共 base 镜像（在根目录做一个 `base-java` Dockerfile）或统一模板，减少维护成本。
- healthcheck 用 `nc -z localhost <port>` 需要额外装 `netcat-openbsd`。可改用 spring-boot-actuator 的 readiness 端点（项目已引入 actuator，见 common pom）+ `curl`，更标准。但这是可用性改进，非性能问题，优先级最低。

## 3. 建议落地顺序（按性价比）

| 优先级 | 动作 | 改动量 | 收益 | 风险 |
|--------|------|--------|------|------|
| **P0** | 每个服务目录加 `.dockerignore` | 极小（6 个文件） | 构建 context 传输从数百 MB 降到几 MB | 几乎零 |
| **P1** | 开启 spring-boot layers + Dockerfile 分层 COPY | 中（根 pom + 6 个 Dockerfile） | 迭代时依赖层 60~85 MB 命中缓存，只重建应用层 | 低，需验证启动正常 |
| **P2** | Maven 多阶段进 Dockerfile | 大 | 构建可复现 | 中（本地迭代变慢），建议做成可选 |
| **P3** | 非 root 运行 | 中 | 安全基线 | 中（需逐服务验证写权限） |
| **P4** | 抽公共 base + 改 healthcheck | 中 | 维护性 | 低 |

## 4. 我的推荐

**当前这个阶段，先做 P0 + P1 就足够**：

1. **P0 加 `.dockerignore`**（6 个服务各一份，排除 `target/` 等）——解决 context 传输慢，零风险。
2. **P1 开 spring-boot 分层构建**——解决「改一行代码要重建 80 MB jar 层」的核心痛点，这是当前迭代节奏下收益最大的一项（项目几乎每轮 Sprint 都要多次 rebuild 所有消费方容器）。

P2/P3/P4 不急于现在做，可留到 CI/发布流程落地时一并处理。

## 5. 后续行动

- [x] **P0** 根级 `.dockerignore`
- [x] **P1** 根 pom 开 layers + 6 个 Dockerfile 分层 COPY
- [x] **P4** 6 个 Dockerfile 集中到 `docker/` 统一管理
- [x] **验证**：6 个镜像构建成功 + 分层结构生效 + 全量部署 `docker compose up -d`，6 个服务全部 healthy，网关登录 E2E 通过

## 6. 部署中发现并修复的代码问题（P1 连带）

全量部署时发现 `app-gateway` 启动失败，根因与分层无关，是 **common 共享模块对 WebFlux 网关不兼容**：

- **现象**：`Error creating bean 'requestMappingHandlerAdapter' ... Failed to introspect Class [com.datanest.common.config.GlobalExceptionHandler]`，`Caused by NoClassDefFoundError: org.springframework.web.servlet.resource.NoResourceFoundException`。
- **根因**：`GlobalExceptionHandler` 是 MVC 专属的 `@RestControllerAdvice`，其 `@ExceptionHandler(NoResourceFoundException.class)` 引用了 servlet 类型。网关是 WebFlux，classpath 无 `spring-webmvc`，WebFlux 的 `RequestMappingHandlerAdapter` 反射 introspect 该 advice 方法签名时 `NoClassDefFoundError` → 启动失败。
- **修复**：`CommonExceptionAutoConfiguration`（`@AutoConfiguration`，负责 `@Bean globalExceptionHandler()` 注入）加 `@ConditionalOnWebApplication(type = SERVLET)`，网关(WebFlux)下不注册该 MVC advice。**条件必须放在 `@AutoConfiguration` 配置类上，而非 `GlobalExceptionHandler` 类上**——`@Conditional` 对组件扫描/`@RestControllerAdvice` 的普通 bean 注册不生效。
- **影响**：MVC 服务（engineering/governance/system/job/worker）行为不变；网关不再注册该 advice（网关鉴权走 Sa-Token filter，不依赖此 advice）。
