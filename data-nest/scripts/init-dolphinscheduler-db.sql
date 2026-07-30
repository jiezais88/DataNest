-- =====================================================
-- DataNest Sprint 3 — DolphinScheduler 元数据库初始化
-- 用途：复用 nacos-mysql 容器，新建 dolphinscheduler 库
-- 加载时机：docker-compose 启动 nacos-mysql 时由 entrypoint 自动执行
-- 编号：04-init-ds-db.sql（继 01/02/03 之后）
-- =====================================================

CREATE
DATABASE IF NOT EXISTS dolphinscheduler
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

-- 授权 nacos 用户完整访问 dolphinscheduler 库
-- 注意：MySQL 8.0+ 创建用户后才能 GRANT，这里用 root 身份在 initdb 中执行
-- 但 docker-entrypoint-initdb.d 里的脚本以 MYSQL_USER 指定的用户身份执行
-- 解决方法：直接以 root 身份跑 GRANT（MYSQL_ROOT_PASSWORD 已设）

GRANT ALL PRIVILEGES ON dolphinscheduler.* TO
'nacos'@'%';
FLUSH
PRIVILEGES;
