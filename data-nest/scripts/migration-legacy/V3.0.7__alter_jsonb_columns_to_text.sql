-- 修复 MyBatis Plus JacksonTypeHandler 与 PostgreSQL jsonb 列不兼容的问题
-- 将相关列从 jsonb 改为 text，与 collect_task.scope 保持一致
ALTER TABLE sync_job ALTER COLUMN source_tables TYPE TEXT USING source_tables::TEXT;
ALTER TABLE sync_job ALTER COLUMN field_mapping TYPE TEXT USING field_mapping::TEXT;
ALTER TABLE field_type_standard ALTER COLUMN allowed_types TYPE TEXT USING allowed_types::TEXT;
