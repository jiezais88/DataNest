ALTER TABLE dag_execution
    ADD COLUMN IF NOT EXISTS error_message TEXT;

COMMENT
ON COLUMN dag_execution.error_message IS '执行失败原因（如 DS 触发失败、工作流未上线等），用于历史列表/详情展示';
