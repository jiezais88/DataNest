# Sprint 0-3 文档/原型/代码资产映射

> 用于 Sprint 0-3 文档/原型与代码对齐工作的快速索引。

## Sprint 0：用户与权限管理

| 类型         | 路径                                                       |
|--------------|------------------------------------------------------------|
| PRD          | `docs/sprint0/DataNest-Sprint0-用户与权限管理-PRD.md`      |
| 技术文档     | `docs/sprint0/DataNest-Sprint0-技术文档.md`                |
| 原型         | `docs/sprint0/ui/方案B-现代企业亮色风格.html`              |
| 测试文档     | `docs/sprint0/DataNest-Sprint0-用户与权限管理-测试文档.md` |
| 后端         | `data-nest-system/src/main/java/com/datanest/system/`      |
| 前端登录     | `data-nest-frontend/src/pages/login/`                      |
| 前端用户管理 | `data-nest-frontend/src/pages/system/users/`               |
| 前端角色常量 | `data-nest-frontend/src/constants/roles.ts`                |
| Flyway       | `data-nest-system/src/main/resources/db/migration/V1.*`    |

## Sprint 1：数据源连接与元数据采集

| 类型            | 路径                                                                                                                                  |
|-----------------|---------------------------------------------------------------------------------------------------------------------------------------|
| PRD             | `docs/sprint1/DataNest-Sprint1-数据源连接与元数据采集-PRD.md`                                                                         |
| 技术文档        | `docs/sprint1/DataNest-Sprint1-技术文档.md`                                                                                           |
| 原型            | `docs/sprint1/ui/Sprint1-数据源与元数据采集.html`                                                                                     |
| 后端-数据源     | `data-nest-engineering/src/main/java/com/datanest/engineering/controller/DataSourceController.java`、`service/DataSourceService.java` |
| 后端-采集任务   | `data-nest-governance/src/main/java/com/datanest/governance/controller/CollectTaskController.java`、`service/CollectTaskService.java` |
| 后端-元数据管理 | `data-nest-governance/src/main/java/com/datanest/governance/controller/MetadataController.java`、`service/MetadataService.java`       |
| 后端-采集执行   | `data-nest-task-core/src/main/java/com/datanest/task/core/collect/`、`service/CollectExecutor.java`                                   |
| 前端-数据源     | `data-nest-frontend/src/pages/engineering/datasources/`                                                                               |
| 前端-采集任务   | `data-nest-frontend/src/pages/governance/collect-tasks/`                                                                              |
| 前端-元数据     | `data-nest-frontend/src/pages/governance/metadata/`                                                                                   |
| Flyway          | `data-nest-system/src/main/resources/db/migration/V2.0.*`                                                                             |

## Sprint 2：批量数据同步与数据标准

| 类型          | 路径                                                                                                                                               |
|---------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| PRD           | `docs/sprint2/DataNest-Sprint2-批量数据同步与数据标准-PRD.md`                                                                                      |
| 技术文档      | `docs/sprint2/DataNest-Sprint2-技术文档.md`                                                                                                        |
| 原型          | `docs/sprint2/ui/Sprint2-批量数据同步与数据标准.html`                                                                                              |
| 后端-同步任务 | `data-nest-engineering/src/main/java/com/datanest/engineering/controller/SyncJobController.java`、`service/SyncJobService.java`                    |
| 后端-同步执行 | `data-nest-task-core/src/main/java/com/datanest/task/core/service/AddaxJobService.java`、`SyncJobExecutorService.java`、`job/SyncJobExecutor.java` |
| 后端-worker   | `data-nest-worker/src/main/java/com/datanest/worker/`                                                                                              |
| 后端-数据标准 | `data-nest-governance/src/main/java/com/datanest/governance/controller/DataStandardController.java`、FieldTypeStandard/NamingStandard              |
| 前端-同步任务 | `data-nest-frontend/src/pages/engineering/sync-jobs/`                                                                                              |
| 前端-数据标准 | `data-nest-frontend/src/pages/governance/data-standards/`                                                                                          |
| Flyway        | `data-nest-system/src/main/resources/db/migration/V2.1.*`、`V2.2.*`                                                                                |

## Sprint 3：DAG 编排与 SQL 任务编辑器

| 类型          | 路径                                                                                                                                                 |
|---------------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| PRD           | `docs/sprint3/DataNest-Sprint3-DAG编排与SQL任务编辑器-PRD.md`                                                                                        |
| 技术文档      | `docs/sprint3/DataNest-Sprint3-技术文档.md`                                                                                                          |
| 实施计划      | `docs/sprint3/DataNest-Sprint3-实施计划.md`                                                                                                          |
| 踩坑记录      | `docs/sprint3/DataNest-Sprint3-Phase1踩坑.md`                                                                                                        |
| 原型          | `docs/sprint3/ui/Sprint3-DAG编排与SQL任务编辑器.html`                                                                                                |
| 后端-DAG      | `data-nest-engineering/src/main/java/com/datanest/engineering/controller/DagController.java`、`service/DagService.java`、`DagProjectController.java` |
| 后端-DAG 执行 | `data-nest-engineering/src/main/java/com/datanest/engineering/service/DagExecutionService.java`、`DagExecutionController.java`                       |
| 后端-DS 同步  | `data-nest-engineering/src/main/java/com/datanest/engineering/service/DolphinSchedulerClient.java`、`DagDsConverter.java`                            |
| 后端-SQL 执行 | `data-nest-task-core/src/main/java/com/datanest/task/core/service/DorisSqlExecutor.java`、`SqlStatementSplitter.java`                                |
| 后端-执行同步 | `data-nest-task-core/src/main/java/com/datanest/task/core/service/DagExecutionSyncService.java`                                                      |
| 前端-DAG      | `data-nest-frontend/src/pages/engineering/dags/`                                                                                                     |
| 前端-执行历史 | `data-nest-frontend/src/pages/engineering/dag-executions/`                                                                                           |
| Flyway        | `data-nest-system/src/main/resources/db/migration/V3.*`                                                                                              |
