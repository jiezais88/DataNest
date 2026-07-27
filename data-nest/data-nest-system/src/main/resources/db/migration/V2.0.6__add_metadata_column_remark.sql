-- V2.0.6 — 元数据字段增加备注列

ALTER TABLE metadata_column
    ADD COLUMN IF NOT EXISTS remark TEXT DEFAULT NULL;

COMMENT
ON COLUMN metadata_column.remark IS '业务口径、枚举值说明等补充信息，可人工编辑';
