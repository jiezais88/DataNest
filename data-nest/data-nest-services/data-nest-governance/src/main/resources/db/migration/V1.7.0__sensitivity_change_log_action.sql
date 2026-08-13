ALTER TABLE public.sensitivity_change_log ADD COLUMN IF NOT EXISTS action character varying(20) DEFAULT 'CHANGE_LEVEL'::character varying NOT NULL;
COMMENT ON COLUMN public.sensitivity_change_log.action IS '审计动作（Sprint 10 F5）：CHANGE_LEVEL 改级 / API_EXEMPT 开白';
ALTER TABLE public.sensitivity_change_log ADD COLUMN IF NOT EXISTS remark character varying(200);
COMMENT ON COLUMN public.sensitivity_change_log.remark IS '动作补充说明（API_EXEMPT 时的 开白/取消开白）';
