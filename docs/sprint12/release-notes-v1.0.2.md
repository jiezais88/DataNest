# DataNest v1.0.2

首页趋势图视觉修复补丁。

## 修复内容

- **首页趋势图贴边失败点定位偏移**：末/首日数据点的失败红点此前只做单边 6px 钳制，与折线顶点脱开；趋势坐标系改为左右各 8px 内边距，红点与折线顶点天然重合且不再被画布裁切。README 首页截图已同步更新。

## 附件说明

本 Release 不含 jar 附件。Flink CDC 运行时依赖（pinned）仍挂在 [v1.0.0 Release](https://github.com/jiezais88/DataNest/releases/tag/v1.0.0)，`deploy.sh` 会自动从该处拉取并校验 sha256。

## 快速开始

```bash
git clone https://github.com/jiezais88/DataNest.git
cd DataNest/data-nest
./deploy.sh
```

完整变更见 [CHANGELOG.md](CHANGELOG.md) · 部署文档 [docs/deploy.md](docs/deploy.md)
