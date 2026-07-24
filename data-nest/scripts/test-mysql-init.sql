-- 数据源 E2E 测试目标库初始化脚本
-- 创建测试用户、数据库及示例表

CREATE
DATABASE IF NOT EXISTS testdb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 确保 testuser 有权限访问 testdb
GRANT ALL PRIVILEGES ON testdb.* TO
'testuser'@'%';
FLUSH
PRIVILEGES;

USE
testdb;

CREATE TABLE IF NOT EXISTS users
(
    id
    BIGINT
    AUTO_INCREMENT
    PRIMARY
    KEY,
    username
    VARCHAR
(
    64
) NOT NULL,
    email VARCHAR
(
    128
) DEFAULT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT
IGNORE INTO users (id, username, email) VALUES (1, 'tester', 'tester@datanest.io');
