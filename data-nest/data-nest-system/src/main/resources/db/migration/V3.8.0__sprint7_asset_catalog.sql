-- ============================================
-- V3.8.0__sprint7_asset_catalog.sql
-- Sprint 7 F1：数据资产目录——分类体系表 + metadata_table 分类/负责人字段
-- 说明：asset_classification 为数据域(DOMAIN)→主题(TOPIC)两级分类字典表；
--       metadata_table 冗余存分类名称（data_domain/data_topic）便于展示与浏览匹配；
--       updated_at 按审计约定不加 DB 默认值（对齐 V3.6.8），仅更新时写入。
-- 注意：本脚本采用紧凑单行 SQL 写法，规避格式化工具拆行导致 checksum 不匹配。
-- ============================================

CREATE TABLE IF NOT EXISTS asset_classification (
    id BIGSERIAL PRIMARY KEY,
    level VARCHAR(20) NOT NULL,
    name VARCHAR(100) NOT NULL,
    parent_id BIGINT,
    sort INT NOT NULL DEFAULT 0,
    created_by BIGINT,
    updated_by BIGINT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT uk_asset_classification_level_name UNIQUE (level, name)
);

CREATE INDEX IF NOT EXISTS idx_asset_classification_level ON asset_classification (level);
CREATE INDEX IF NOT EXISTS idx_asset_classification_parent_id ON asset_classification (parent_id);

COMMENT ON TABLE asset_classification IS '数据资产分类体系（Sprint 7 F1，数据域→主题两级）';
COMMENT ON COLUMN asset_classification.level IS '层级：DOMAIN 数据域（一级）/ TOPIC 主题（二级）';
COMMENT ON COLUMN asset_classification.name IS '分类名称（同级唯一）';
COMMENT ON COLUMN asset_classification.parent_id IS '父分类 ID（TOPIC 指向 DOMAIN；DOMAIN 为 NULL）';
COMMENT ON COLUMN asset_classification.sort IS '同级排序号';

ALTER TABLE metadata_table ADD COLUMN IF NOT EXISTS data_domain VARCHAR(100);
ALTER TABLE metadata_table ADD COLUMN IF NOT EXISTS data_topic VARCHAR(100);
ALTER TABLE metadata_table ADD COLUMN IF NOT EXISTS owner_user_id BIGINT;

COMMENT ON COLUMN metadata_table.data_domain IS '数据域（一级分类名，冗余存名称便于展示，Sprint 7 F1）';
COMMENT ON COLUMN metadata_table.data_topic IS '主题（二级分类名，冗余存名称，Sprint 7 F1）';
COMMENT ON COLUMN metadata_table.owner_user_id IS '表负责人用户 ID（关联 sys_user.id，Sprint 7 F1）';
