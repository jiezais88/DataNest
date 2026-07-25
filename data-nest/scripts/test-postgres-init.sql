-- PostgreSQL 数据源 E2E 测试库初始化
CREATE
DATABASE testdb;

\c
testdb;

CREATE
USER testuser WITH PASSWORD 'testpass123';
GRANT ALL PRIVILEGES ON DATABASE
testdb TO testuser;

CREATE SCHEMA IF NOT EXISTS public;
GRANT
USAGE
ON
SCHEMA
public TO testuser;
GRANT ALL PRIVILEGES ON SCHEMA
public TO testuser;
GRANT ALL PRIVILEGES ON ALL
TABLES IN SCHEMA public TO testuser;
ALTER
DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO testuser;

CREATE TABLE users
(
    id         SERIAL PRIMARY KEY,
    username   VARCHAR(64) NOT NULL,
    email      VARCHAR(128),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT
ON TABLE users IS '测试用户表';
COMMENT
ON COLUMN users.username IS '用户名';
COMMENT
ON COLUMN users.email IS '邮箱';

INSERT INTO users (username, email)
VALUES ('pg_user_1', 'pg1@example.com'),
       ('pg_user_2', 'pg2@example.com');
