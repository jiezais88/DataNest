-- api_call_log 冗余 key_name 快照（Key 物理删除后统计仍可显示原名；Sprint 10 F4 产品评审：保留调用审计，展示优雅化）
ALTER TABLE api_call_log ADD COLUMN key_name VARCHAR(100);
