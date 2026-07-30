-- gvenzl/oracle-free 以 SYSTEM 执行初始化脚本，需显式指定 schema
ALTER
SESSION SET CURRENT_SCHEMA = testuser;

CREATE TABLE testuser.users
(
    id         NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username   VARCHAR2(50) NOT NULL,
    email      VARCHAR2(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT
ON TABLE testuser.users IS '测试用户表';
COMMENT
ON COLUMN testuser.users.id IS '主键';
COMMENT
ON COLUMN testuser.users.username IS '用户名';
COMMENT
ON COLUMN testuser.users.email IS '邮箱';
COMMENT
ON COLUMN testuser.users.created_at IS '创建时间';

INSERT INTO testuser.users (username, email)
VALUES ('alice', 'alice@example.com');
INSERT INTO testuser.users (username, email)
VALUES ('bob', 'bob@example.com');
INSERT INTO testuser.users (username, email)
VALUES ('charlie', 'charlie@example.com');

COMMIT;
