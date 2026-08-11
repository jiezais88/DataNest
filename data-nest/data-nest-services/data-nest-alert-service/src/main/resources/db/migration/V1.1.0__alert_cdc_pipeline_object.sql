-- Sprint 9 F3：流处理告警接入（CDC 管道对象）
-- ① 放宽 alert_rule.object_type CHECK，新增 CDC_PIPELINE
-- ② 放宽 alert_history.alert_type CHECK，新增 LAG_EXCEEDED（延迟超阈值）/ EXTERNAL_STOP（外部停止）
--    （技术文档 D-D5 原写「alert_history.alert_type 无 CHECK」，实际 baseline 有 CHECK，用户 2026-08-11 确认一并放宽并修正文档）
ALTER TABLE public.alert_rule DROP CONSTRAINT alert_rule_object_type_check;
ALTER TABLE public.alert_rule ADD CONSTRAINT alert_rule_object_type_check CHECK (((object_type)::text = ANY ((ARRAY['DAG'::character varying, 'SYNC_JOB'::character varying, 'COLLECT_TASK'::character varying, 'QUALITY'::character varying, 'CDC_PIPELINE'::character varying])::text[])));
COMMENT ON COLUMN public.alert_rule.object_type IS '对象类型：DAG / SYNC_JOB / COLLECT_TASK / QUALITY / CDC_PIPELINE';

ALTER TABLE public.alert_history DROP CONSTRAINT alert_history_alert_type_check;
ALTER TABLE public.alert_history ADD CONSTRAINT alert_history_alert_type_check CHECK (((alert_type)::text = ANY ((ARRAY['FAILURE'::character varying, 'TIMEOUT'::character varying, 'SUCCESS'::character varying, 'LAG_EXCEEDED'::character varying, 'EXTERNAL_STOP'::character varying])::text[])));
COMMENT ON COLUMN public.alert_history.alert_type IS '告警类型：FAILURE / TIMEOUT / SUCCESS / LAG_EXCEEDED / EXTERNAL_STOP';
