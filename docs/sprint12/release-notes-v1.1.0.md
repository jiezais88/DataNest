# DataNest v1.1.0

首页整体重设计为「运营仪表盘」，并修复部署与视觉问题。

## 首页 v5 运营仪表盘

推倒值班台式设计重来——任何状态下信息密度恒定，无异常时页面不再塌陷：

- **R1**：问候 + 平台状态徽标 + 24h 告警 + 自动刷新
- **R2 统计卡 ×5**：数据源 / 数据表 / 调度任务 / 数据 API（规模感）+ 唯一 amber 高亮的待处理异常风险卡（可点击钻取）
- **R3 运行态势**：14 日运行面积图主视觉，今日状态分段条收进卡头（indigo=成功 / red=失败 / sky=运行中，每段可钻取）；右栏系统健康 5 项 + 快捷操作
- **R4 三栏**：待处理异常（紧凑行 + 行内重跑）│ 失败任务排行 TOP5（近 14 日）│ 最近运行 feed

配套后端聚合扩展：失败任务排行、最近运行 feed、数据源/任务/数据表规模计数。

## 修复

- **Docker daemon 重启服务一窝蜂启动**：新增统一启动入口 `wait-and-start.sh`，JVM 启动前等待中间件就绪（模拟重启验证 0 崩溃收敛）
- 趋势图失败红点 SVG 拉伸变形 + 贴边偏移（改 HTML 圆点 + 坐标系内边距）
- 非语义红绿清理：成功段/血缘高亮改 indigo，资产详情统计卡统一主色
- 一屏零滚动适配：1440×900 / 1920×1080 满档 + ≤700px 矮窗口自动紧凑密度

## 附件说明

本 Release 不含 jar 附件。Flink CDC 运行时依赖（pinned）仍挂在 [v1.0.0 Release](https://github.com/jiezais88/DataNest/releases/tag/v1.0.0)，`deploy.sh` 自动拉取并校验 sha256。

## 快速开始

```bash
git clone https://github.com/jiezais88/DataNest.git
cd DataNest/data-nest
./deploy.sh
```

完整变更见 [CHANGELOG.md](CHANGELOG.md) · 部署文档 [docs/deploy.md](docs/deploy.md)
