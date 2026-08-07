-- system 域基线（微服务化阶段 5：从共享 datanest 库 pg_dump --schema-only 生成，含当前全部 DDL/索引/约束/注释）
-- 后续演进脚本版本号必须大于 1.0.0（如 V1.1.0），紧凑单行风格
\restrict XxPLDe7tNXTA2KYPvSXiCECkNVYzgClmwzl2zIeGGl6tUTSpeQZFiwHrMvh1THp
CREATE TABLE public.sys_permission (
    id bigint NOT NULL,
    code character varying(50) NOT NULL,
    name character varying(100) NOT NULL,
    description character varying(200) DEFAULT NULL::character varying,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);
COMMENT ON TABLE public.sys_permission IS '系统权限';
COMMENT ON COLUMN public.sys_permission.code IS '权限编码';
COMMENT ON COLUMN public.sys_permission.name IS '权限名称';
CREATE TABLE public.sys_role (
    id bigint NOT NULL,
    code character varying(30) NOT NULL,
    name character varying(50) NOT NULL,
    description character varying(200) DEFAULT NULL::character varying,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);
COMMENT ON TABLE public.sys_role IS '系统角色';
COMMENT ON COLUMN public.sys_role.id IS '主键ID';
COMMENT ON COLUMN public.sys_role.code IS '角色编码';
COMMENT ON COLUMN public.sys_role.name IS '角色名称';
COMMENT ON COLUMN public.sys_role.description IS '角色描述';
CREATE TABLE public.sys_role_permission (
    id bigint NOT NULL,
    role_id bigint NOT NULL,
    permission_id bigint NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);
COMMENT ON TABLE public.sys_role_permission IS '角色权限关联';
COMMENT ON COLUMN public.sys_role_permission.role_id IS '角色ID';
COMMENT ON COLUMN public.sys_role_permission.permission_id IS '权限ID';
CREATE TABLE public.sys_user (
    id bigint NOT NULL,
    username character varying(30) NOT NULL,
    password character varying(255) NOT NULL,
    email character varying(100) DEFAULT NULL::character varying,
    phone character varying(20) DEFAULT NULL::character varying,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    created_by bigint,
    updated_by bigint
);
COMMENT ON TABLE public.sys_user IS '系统用户';
COMMENT ON COLUMN public.sys_user.id IS '主键ID (雪花算法)';
COMMENT ON COLUMN public.sys_user.username IS '用户名';
COMMENT ON COLUMN public.sys_user.password IS '密码 (BCrypt)';
COMMENT ON COLUMN public.sys_user.email IS '邮箱';
COMMENT ON COLUMN public.sys_user.phone IS '手机号';
COMMENT ON COLUMN public.sys_user.enabled IS '是否启用';
COMMENT ON COLUMN public.sys_user.created_at IS '创建时间';
COMMENT ON COLUMN public.sys_user.updated_at IS '更新时间';
COMMENT ON COLUMN public.sys_user.created_by IS '创建人 (sys_user.id)';
COMMENT ON COLUMN public.sys_user.updated_by IS '修改人 (sys_user.id)';
CREATE TABLE public.sys_user_role (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    role_id bigint NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);
COMMENT ON TABLE public.sys_user_role IS '用户角色关联';
COMMENT ON COLUMN public.sys_user_role.user_id IS '用户ID';
COMMENT ON COLUMN public.sys_user_role.role_id IS '角色ID';
ALTER TABLE ONLY public.sys_permission
    ADD CONSTRAINT sys_permission_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.sys_role_permission
    ADD CONSTRAINT sys_role_permission_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.sys_role
    ADD CONSTRAINT sys_role_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.sys_user
    ADD CONSTRAINT sys_user_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.sys_user_role
    ADD CONSTRAINT sys_user_role_pkey PRIMARY KEY (id);
CREATE UNIQUE INDEX uk_sys_permission_code ON public.sys_permission USING btree (code);
CREATE UNIQUE INDEX uk_sys_role_code ON public.sys_role USING btree (code);
CREATE UNIQUE INDEX uk_sys_role_permission ON public.sys_role_permission USING btree (role_id, permission_id);
CREATE UNIQUE INDEX uk_sys_user_role ON public.sys_user_role USING btree (user_id, role_id);
CREATE UNIQUE INDEX uk_sys_user_username ON public.sys_user USING btree (username);
\unrestrict XxPLDe7tNXTA2KYPvSXiCECkNVYzgClmwzl2zIeGGl6tUTSpeQZFiwHrMvh1THp
