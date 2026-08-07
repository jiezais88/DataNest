-- 同步日志按表标记 + 分页：每行日志标注所属表（平台概要行为 NULL 归「概览」），支持按表独立分页
ALTER TABLE sync_job_log
    ADD COLUMN table_name VARCHAR(100);

CREATE INDEX idx_sync_job_log_hist_table ON sync_job_log (history_id, table_name, line_num);
