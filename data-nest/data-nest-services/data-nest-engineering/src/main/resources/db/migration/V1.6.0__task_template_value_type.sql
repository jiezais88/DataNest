-- ============================================
-- V1.6.0__task_template_value_type.sql
-- Sprint 7 任务模板库增强（2026-08-10 产品决策）：模板一键创建占位符支持下拉。
-- 1) 内置模板占位符 valueType 升级：
--    SOURCE_DATABASE（源库/Schema 下拉）/ SOURCE_TABLE（源表下拉）/
--    TARGET_DATABASE（Doris 库下拉）/ TARGET_TABLE（Doris 表下拉）/
--    INCREMENTAL_FIELD（增量字段下拉）/ SCOPE（采集库下拉）
-- 2) SYNC 模板 config 新增 source_schema 占位符（不在 placeholders 声明，前端提交时
--    按数据源类型自动注入）：有模式（PG/Oracle/SQLServer）→ source_db=数据源库名、
--    source_schema=选中的 Schema；无模式 → 两者同值为选中的库。
--    对齐普通表单 resolveDatabaseSchema 语义。
-- 注意：本脚本采用紧凑单行 SQL 写法（规避格式化工具拆行导致 checksum 不匹配）；
--       updated_at 仅更新时由代码写入，DB 不设默认值。
-- ============================================

UPDATE task_template SET config_template = '{"placeholders":[{"key":"source_datasource","label":"源数据源","required":true,"valueType":"DATASOURCE"},{"key":"source_db","label":"源库名","required":true,"valueType":"SOURCE_DATABASE"},{"key":"source_table","label":"源表名","required":true,"valueType":"SOURCE_TABLE"},{"key":"target_db","label":"目标库","required":true,"valueType":"TARGET_DATABASE"},{"key":"target_table","label":"目标表","required":true,"valueType":"TARGET_TABLE"}],"config":{"sourceDatasourceId":"{source_datasource}","sourceDatabase":"{source_db}","sourceSchema":"{source_schema}","sourceTables":["{source_table}"],"syncMode":"FULL","triggerType":"MANUAL","targetDatabase":"{target_db}","targetTable":"{target_table}"}}', updated_at = CURRENT_TIMESTAMP WHERE id = 910000000000000001;
UPDATE task_template SET config_template = '{"placeholders":[{"key":"source_datasource","label":"源数据源","required":true,"valueType":"DATASOURCE"},{"key":"source_db","label":"源库名","required":true,"valueType":"SOURCE_DATABASE"},{"key":"source_table","label":"源表名","required":true,"valueType":"SOURCE_TABLE"},{"key":"incremental_field","label":"增量字段","required":true,"valueType":"INCREMENTAL_FIELD"},{"key":"target_db","label":"目标库","required":true,"valueType":"TARGET_DATABASE"},{"key":"target_table","label":"目标表","required":true,"valueType":"TARGET_TABLE"},{"key":"schedule_cron","label":"调度 Cron","required":false,"defaultValue":"0 0 2 * * ?"}],"config":{"sourceDatasourceId":"{source_datasource}","sourceDatabase":"{source_db}","sourceSchema":"{source_schema}","sourceTables":["{source_table}"],"syncMode":"INCREMENTAL","incrementalField":"{incremental_field}","triggerType":"CRON","cronExpression":"{schedule_cron}","targetDatabase":"{target_db}","targetTable":"{target_table}"}}', updated_at = CURRENT_TIMESTAMP WHERE id = 910000000000000002;
UPDATE task_template SET config_template = '{"placeholders":[{"key":"datasource","label":"数据源","required":true,"valueType":"DATASOURCE"},{"key":"scope","label":"采集库名（schema）","required":true,"valueType":"SCOPE"}],"config":{"datasourceId":"{datasource}","scope":["{scope}"],"collectMode":"FULL","triggerType":"MANUAL"}}', updated_at = CURRENT_TIMESTAMP WHERE id = 910000000000000003;
