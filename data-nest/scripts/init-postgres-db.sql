-- ============================================
-- DataNest 业务域库初始化（Sprint 12 补）
-- 按域拆分 6 库，各服务 Flyway 独立管理本库表结构。
-- 此前 6 库靠老卷存活、从未在 initdb 落地，全新部署时缺失导致 6 个持库服务全部起不来。
-- 挂载点：docker-compose.yml middleware-postgres → /docker-entrypoint-initdb.d/
-- ============================================
CREATE DATABASE datanest_system;
CREATE DATABASE datanest_alert;
CREATE DATABASE datanest_engineering;
CREATE DATABASE datanest_governance;
CREATE DATABASE datanest_realtime;
CREATE DATABASE datanest_dataservice;
