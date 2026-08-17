# DataNest v1.0.1

全新部署初始化修复补丁（卸载重装演练抓出）。**建议所有新用户直接使用本版本部署**——v1.0.0 在全新空数据卷场景下存在初始化阻断问题。

## 修复内容

- **全新部署初始化修复**（老数据卷掩盖、全新空卷首次暴露，共 4 项）：
  - Nacos 初始化 schema 补默认用户种子——此前全新 MySQL 卷登录探针 500，全栈启动卡死
  - PostgreSQL initdb 新增 6 个业务域库——此前 6 域库靠老卷存活，持库服务全部起不来；旧共享库 `datanest` 同时废弃，不再初始化
  - 4 个服务 Flyway baseline 删除 pg_dump 附带的 psql 元命令（`\restrict/\unrestrict`）——此前全新库首个迁移即失败
  - 补种子数据迁移：预置 4 角色 + admin + 角色权限矩阵、质量规则内置模板 ×4——此前全新库无法登录、质量模板缺失
- **血缘批量写入过滤空 target 记录**：含 DROP/USE 语句的 SQL 节点不再触发整批失败 + 熔断丢血缘

## 其他

- README 增加「界面展示」区块（6 张实拍截图）

## 附件说明

本 Release 不含 jar 附件。Flink CDC 运行时依赖（pinned）仍挂在 [v1.0.0 Release](https://github.com/jiezais88/DataNest/releases/tag/v1.0.0)，`deploy.sh` 会自动从该处拉取并校验 sha256。

## 快速开始

```bash
git clone https://github.com/jiezais88/DataNest.git
cd DataNest/data-nest
./deploy.sh
```

完整变更见 [CHANGELOG.md](CHANGELOG.md) · 部署文档 [docs/deploy.md](docs/deploy.md)
