-- ============================================
-- V1.4.0__sprint8_asset_collaboration.sql
-- Sprint 8 F1 资产目录深化（DC-06~09）：资产协作 6 表
--   asset_tag 标签字典 / asset_table_tag 表-标签关联 / asset_favorite 收藏 /
--   asset_follow 关注 / asset_comment 评论 / asset_view_log 热度按天聚合
-- 注意：本脚本采用紧凑单行 SQL 写法，规避格式化工具拆行导致 checksum 不匹配；
--       主键均为应用侧雪花 ID（IdType.ASSIGN_ID），不建序列；
--       updated_at 不设 DB 默认值（审计字段约定：仅真正更新时由代码写入）；
--       asset_comment 补 deleted_by/deleted_at（2026-08-10 用户确认：治理员/超管删评论记录删除人）。
-- ============================================

CREATE TABLE IF NOT EXISTS asset_tag (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    created_by bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT asset_tag_pkey PRIMARY KEY (id),
    CONSTRAINT uk_asset_tag_name UNIQUE (name)
);
COMMENT ON TABLE asset_tag IS '资产标签字典（Sprint 8 DC-06，平台级标签，同名复用）';
COMMENT ON COLUMN asset_tag.name IS '标签名（全局唯一）';
COMMENT ON COLUMN asset_tag.created_by IS '创建人ID';

CREATE TABLE IF NOT EXISTS asset_table_tag (
    id bigint NOT NULL,
    table_id bigint NOT NULL,
    tag_id bigint NOT NULL,
    created_by bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT asset_table_tag_pkey PRIMARY KEY (id),
    CONSTRAINT uk_asset_table_tag UNIQUE (table_id, tag_id)
);
CREATE INDEX IF NOT EXISTS idx_asset_table_tag_tag ON asset_table_tag (tag_id);
COMMENT ON TABLE asset_table_tag IS '表-标签关联（Sprint 8 DC-06，多对多；表删除级联清绑定，标签无引用时物理删字典）';
COMMENT ON COLUMN asset_table_tag.table_id IS 'metadata_table.id';
COMMENT ON COLUMN asset_table_tag.tag_id IS 'asset_tag.id';

CREATE TABLE IF NOT EXISTS asset_favorite (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    table_id bigint NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT asset_favorite_pkey PRIMARY KEY (id),
    CONSTRAINT uk_asset_favorite UNIQUE (user_id, table_id)
);
COMMENT ON TABLE asset_favorite IS '资产收藏（Sprint 8 DC-07，个人维度；表删除级联清理）';
COMMENT ON COLUMN asset_favorite.user_id IS '收藏人（sys_user.id）';
COMMENT ON COLUMN asset_favorite.table_id IS 'metadata_table.id';
COMMENT ON COLUMN asset_favorite.created_at IS '收藏时间';

CREATE TABLE IF NOT EXISTS asset_follow (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    table_id bigint NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT asset_follow_pkey PRIMARY KEY (id),
    CONSTRAINT uk_asset_follow UNIQUE (user_id, table_id)
);
CREATE INDEX IF NOT EXISTS idx_asset_follow_table ON asset_follow (table_id);
COMMENT ON TABLE asset_follow IS '资产关注（Sprint 8 DC-07，个人维度；变更动态复用 collect_change_detail；表删除级联清理）';
COMMENT ON COLUMN asset_follow.user_id IS '关注人（sys_user.id）';
COMMENT ON COLUMN asset_follow.table_id IS 'metadata_table.id';
COMMENT ON COLUMN asset_follow.created_at IS '关注时间';

CREATE TABLE IF NOT EXISTS asset_comment (
    id bigint NOT NULL,
    table_id bigint NOT NULL,
    user_id bigint NOT NULL,
    content character varying(2000) NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    deleted_by bigint,
    deleted_at timestamp without time zone,
    created_by bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT asset_comment_pkey PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_asset_comment_table ON asset_comment (table_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_asset_comment_user ON asset_comment (user_id);
COMMENT ON TABLE asset_comment IS '资产评论（Sprint 8 DC-08，按表维度不嵌套；表删除级联物理删，用户删除保留历史）';
COMMENT ON COLUMN asset_comment.table_id IS 'metadata_table.id';
COMMENT ON COLUMN asset_comment.user_id IS '评论人（sys_user.id；用户删除后前端展示「已注销」）';
COMMENT ON COLUMN asset_comment.content IS '评论内容（≤2000 字）';
COMMENT ON COLUMN asset_comment.deleted IS '软删标记：0-正常 1-已删除';
COMMENT ON COLUMN asset_comment.deleted_by IS '删除人ID（作者自删/治理员/超管删均记录）';
COMMENT ON COLUMN asset_comment.deleted_at IS '删除时间';

CREATE TABLE IF NOT EXISTS asset_view_log (
    id bigint NOT NULL,
    table_id bigint NOT NULL,
    view_date date NOT NULL,
    view_count integer DEFAULT 0 NOT NULL,
    updated_at timestamp without time zone,
    CONSTRAINT asset_view_log_pkey PRIMARY KEY (id),
    CONSTRAINT uk_asset_view_log UNIQUE (table_id, view_date)
);
COMMENT ON TABLE asset_view_log IS '资产热度按天聚合（Sprint 8 DC-09，埋点 upsert 累加；sort=hot 按最近 30 天求和）';
COMMENT ON COLUMN asset_view_log.table_id IS 'metadata_table.id';
COMMENT ON COLUMN asset_view_log.view_date IS '访问日期';
COMMENT ON COLUMN asset_view_log.view_count IS '当日访问数';
COMMENT ON COLUMN asset_view_log.updated_at IS '最近累加时间';
