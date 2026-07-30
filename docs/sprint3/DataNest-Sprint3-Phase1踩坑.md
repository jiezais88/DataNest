# Sprint 3 Phase 1 踩坑记录

> 日期：2026-07-30
> 范围：DolphinScheduler 3.4.2 Docker 部署 + 端到端调度链路验证
> 状态：✅ 全部解决，DS 集群已稳定运行

---

## 1. MySQL 驱动缺失（最关键的坑）

**现象**：

```
java.lang.IllegalStateException: Cannot load driver class: com.mysql.cj.jdbc.Driver
```

**根因**： DolphinScheduler 镜像默认不带 MySQL JDBC 驱动。官方原话：
> "由于商业许可证的原因，我们不能直接使用 MySQL 的驱动包"
> MySQL Connector/J 是 **GPLv2** 协议，与 Apache 2.0 不兼容，Apache 项目不能内置。

**官方支持方案**：

| 方案                 | 操作                                                                                                                                       | 优缺点                               |
|----------------------|--------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------|
| A. 自定义 Dockerfile | `FROM apache/dolphinscheduler-xxx:3.4.2 + COPY mysql-connector-j-8.0.33.jar /opt/dolphinscheduler/libs`                                    | ✅ 最干净，❌ 需 build 4 个镜像      |
| B. Volume 挂载 jar   | `volumes: - ./shared-configs/libs/mysql-connector-j-8.0.33.jar:/opt/dolphinscheduler/<server>-server/libs/mysql-connector-j-8.0.33.jar:ro` | ✅ 不重新 build，⚠️ 重启容器要重新挂 |
| C. docker cp 塞进去  | `docker cp xxx.jar <container>:/opt/dolphinscheduler/libs/`                                                                                | 临时调试用                           |

**本项目选择**：方案 B（4 个服务每个挂一份），jar 来自 `mysql-connector-j-8.0.33.jar` 官方包。

---

## 2. 镜像内部 libs/ 结构跟老版本不一样

**老版本认知（错）**：共享顶层 `/opt/dolphinscheduler/libs/`，挂一次 jar 4 个服务都用。

**3.4.2 实际结构**：

```
/opt/dolphinscheduler/
├── api-server/libs/      ← 独立子目录
├── master-server/libs/   ← 独立子目录
├── worker-server/libs/   ← 独立子目录
├── alert-server/libs/    ← 独立子目录
├── tools/libs/           ← 独立子目录（schema init 用）
└── libs/                 ← 这个也存在但 classpath 不直接用
```

**classpath 启动脚本**（`/opt/dolphinscheduler/api-server/bin/start.sh`）：

```bash
MODULES_PATH=(api-server)
CP=""
for module in ${MODULES_PATH[@]}; do
  CP=$CP:"$DOLPHINSCHEDULER_HOME/$module/libs/*"   # ← 关键：每个 server 自己的 libs
done
```

**结论**：jar 必须挂到每个 server **自己的 libs 子目录**，挂 4 份（tools 容器还要单独挂）。

---

## 3. DS 3.4.2 数据库配置变量名变了

**老版本（< 3.x）**：

```yaml
environment:
  - DATABASE=mysql
  - SPRING_DATASOURCE_URL=...
```

**3.4.2 新版**：

```yaml
environment:
  - SPRING_PROFILES_ACTIVE=mysql   # ← 用 profile 切换，DATABASE=mysql 失效
  - SPRING_DATASOURCE_URL=...
  - SPRING_DATASOURCE_USERNAME=...
  - SPRING_DATASOURCE_PASSWORD=...
```

**现象**：`DATABASE=mysql` 在 3.4.2 不被识别，仍默认 PG，导致 driver 找不到对应的 datasource 配置。

---

## 4. Alpine 镜像内 sh/bash 不可执行

**现象**：

```
/usr/bin/sh: /usr/bin/sh: cannot execute binary file
/usr/bin/bash: /usr/bin/bash: cannot execute binary file
```

**根因**：`apache/dolphinscheduler-tools:3.4.2` 镜像内部 shell 二进制文件本身不可执行（具体原因可能是镜像构建问题或 path
问题），但 `tools/bin/upgrade-schema.sh` 这个 **脚本本身可执行**。

**错的写法**：

```yaml
command: [ "sh", "-c", "tools/bin/upgrade-schema.sh" ]   # 错
command: [ "bash", "-c", "tools/bin/upgrade-schema.sh" ]  # 错
```

**对的写法**：

```yaml
command: [ "tools/bin/upgrade-schema.sh" ]   # 直接传脚本，让镜像 entrypoint 处理
```

**幂等性**：`upgrade-schema.sh` 检查表存在性，已存在会跳过，重复跑无副作用（验证日志：
`The database has been initialized. Skip the initialization step`）。

---

## 5. YAML 缩进错位

**原文件 line 322-325**：

```yaml
ports:
  - "12345:12345"
    volumes:                      # ← 错：8 空格，在 ports list item 内部
      - ./shared-configs/libs/...:...
```

**修复**：

```yaml
ports:
  - "12345:12345"
volumes:                           # ← 对：4 空格，与 ports 同级
  - ./shared-configs/libs/...:...
```

**影响**：原本 docker compose 能解析但容器启动时挂载点不对。

---

## 6. nacos-mysql 初始化时序问题

**背景**：`nacos-mysql` 容器用 `./scripts/init-dolphinscheduler-db.sql` 一次性脚本创建 `dolphinscheduler` 库。但 **这个脚本只在
MySQL 首次启动时执行**（通过 `/docker-entrypoint-initdb.d/`）。

**问题**：如果 nacos-mysql 容器已经运行过（比如 3 小时前启动），重启时不会重新执行 init 脚本，导致 `dolphinscheduler` 库不存在。

**解决方案**：

```powershell
docker exec datanest-middleware-mysql mysql -uroot -proot123 -e "
  CREATE DATABASE IF NOT EXISTS dolphinscheduler DEFAULT CHARACTER SET utf8;
  GRANT ALL PRIVILEGES ON dolphinscheduler.* TO 'nacos'@'%';
  FLUSH PRIVILEGES;"
```

**自动化方案**：把 `init-dolphinscheduler-db.sql` 改成手动 SQL，并通过 `tools/bin/upgrade-schema.sh` 创建 64 张表，幂等可重跑。

---

## 7. 端口冲突（容器改名后）

**场景**：docker-compose.yml 重构后改 service key `app-ds-api` → `middleware-ds-api`，旧容器 `datanest-app-ds-api` 还占着
12345 端口。

**现象**：

```
Error response from daemon: Bind for 0.0.0.0:12345 failed: port is already allocated
```

**解决**：

```powershell
docker compose down --remove-orphans   # 删旧容器
docker compose up -d                   # 起新容器
```

**预防**：每次 docker-compose.yml 大改 service 名，养成先 `down` 再 `up` 的习惯。

---

## 8. ZK 持久化 volumes 缺失

**问题**：最初 docker-compose.yml 里 zookeeper 没挂 volume，重启后 ZK 数据丢失，DS master/worker 重新注册。

**修复**：增加 2 个 named volume：

```yaml
zookeeper:
  volumes:
    - datanest-zk-data:/data
    - datanest-zk-datalog:/datalog
```

**验证**：

```bash
docker exec datanest-middleware-zookeeper ls /data/
# myid  version-2/   ← 注册信息持久化成功
```

---

## 9. 容器命名规范：middleware-* vs app-*

**最终命名**（2026-07-30 杰仔拍板）：

- `middleware-*` = 别人造的轮子（基础设施/中间件）：DB、Cache、Registry、调度
- `app-*` = DataNest 自己写的业务服务

**完整清单**： | 中间件 (middleware- *) | 应用 (app-*) | |---|---| | middleware-mysql | app-gateway | |
middleware-nacos | app-system | | middleware-postgres | app-engineering | | middleware-redis | app-governance | |
middleware-xxljob | app-worker | | middleware-zookeeper | app-job | | middleware-test-mysql | app-frontend | |
middleware-test-postgres | app-ds-api | | middleware-ds-api | app-ds-master | | middleware-ds-master | app-ds-worker | |
middleware-ds-worker | app-ds-alert | | middleware-ds-alert | |

**特殊点**：

- DS 划到中间件（被 DataNest 调度的外部引擎）
- DataNest worker 改名为 `app-worker`（避免跟 `app-ds-worker` 混淆）
- 一次性 init 容器（schema-init / nacos-init）也归 `middleware-*`

**Volume 名保留旧名**（`nacos-mysql-data` / `pgdata` / `redisdata` 等）以兼容历史数据，避免重命名丢数据。

---

## 10. DS 端到端调度链路验证

**目标**：验证 DS master → ZK → DS worker → MySQL 全链路通。

**步骤**：

1. 登录：`POST /dolphinscheduler/login` (admin/dolphinscheduler123)
2. 建租户：`POST /dolphinscheduler/tenants` (tenantCode=datanest)
3. 建项目：`POST /dolphinscheduler/projects` (projectName=data-dev)
4. 建 token：`POST /dolphinscheduler/access-tokens` (token=datanest-ds-token-2026)
5. 生成 task code：`GET /dolphinscheduler/projects/{code}/task-definition/gen-task-codes?genNum=1`
6. 创建 workflow：`POST /dolphinscheduler/projects/{code}/workflow-definition`
7. 发布 workflow：`POST /dolphinscheduler/projects/{code}/workflow-definition/{wfCode}/release` (releaseState=ONLINE)
8. 触发执行：`POST /dolphinscheduler/projects/{code}/executors/start-workflow-instance`
9. 查历史：`GET /dolphinscheduler/projects/{code}/workflow-instances?pageNo=1&pageSize=5`

**触发时的坑**：

- 参数名 `workflowCode` → 实际是 `workflowDefinitionCode`
- 参数名 `startNodes` JSON 数组 → 实际 `startNodeList`，传 `""`（不是 `"[]"`）
- 必须带 `scheduleTime`（空字符串或时间格式都行）

**验证结果**：

```
workflow instance: state=SUCCESS, duration=2s, host=172.21.0.13:5678 (master)
task instance: state=7 (SUCCESS), host=172.21.0.14:1234 (worker)
```

---

## 总结

| 类别                | 数量 | 解决方式                                 |
|---------------------|------|------------------------------------------|
| DS 镜像问题         | 3    | 挂 jar / 用 profile / 不用 shell wrapper |
| docker-compose 配置 | 3    | YAML 缩进 / 端口冲突 / volume 命名       |
| 基础设施问题        | 2    | 手动 CREATE DATABASE / ZK 持久化         |
| 命名规范            | 2    | 分 middleware / app 两层，DS 划中间件    |

**关键学习**：

1. **DS 文档很多，但实际跑起来跟文档有出入**（license 限制、shell 不可用、参数名不一致）— 必须真跑才知道
2. **每个 Apache DS 部署都得自带 MySQL driver**（除非用 PG）— 这是 DS 社区公开的「痛点」
3. **docker-compose 大改 service key 要先 down** — 否则端口冲突
4. **ZK 在生产必须挂 volume** — 否则重启 master/worker 要重新注册
5. **DS 3.4.2 镜像内部 libs 分散** — 不是 1.x/2.x 的共享顶层结构
