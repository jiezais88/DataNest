-- V2.0.2 — 将 collect_task.scope 从 JSONB 改为 TEXT，适配 MyBatis-Plus JacksonTypeHandler 字符串写入。
-- 注意：数据内容仍为 JSON 字符串，默认值为 '[]'。
ALTER TABLE collect_task ALTER COLUMN scope TYPE TEXT USING scope::text;
ALTER TABLE collect_task
    ALTER COLUMN scope SET DEFAULT '[]';
ALTER TABLE collect_task
    ALTER COLUMN scope SET NOT NULL;
