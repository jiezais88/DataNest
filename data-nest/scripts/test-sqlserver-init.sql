-- 创建测试库
IF
DB_ID('testdb') IS NULL
    CREATE
DATABASE testdb;
GO

USE testdb;
GO

CREATE TABLE dbo.users
(
    id         INT IDENTITY(1,1) PRIMARY KEY,
    username   NVARCHAR(50) NOT NULL,
    email      NVARCHAR(100),
    created_at DATETIME2 DEFAULT GETDATE()
);
GO

EXEC sp_addextendedproperty 'MS_Description', N'测试用户表', 'SCHEMA', 'dbo', 'TABLE', 'users';
EXEC sp_addextendedproperty 'MS_Description', N'主键', 'SCHEMA', 'dbo', 'TABLE', 'users', 'COLUMN', 'id';
EXEC sp_addextendedproperty 'MS_Description', N'用户名', 'SCHEMA', 'dbo', 'TABLE', 'users', 'COLUMN', 'username';
EXEC sp_addextendedproperty 'MS_Description', N'邮箱', 'SCHEMA', 'dbo', 'TABLE', 'users', 'COLUMN', 'email';
EXEC sp_addextendedproperty 'MS_Description', N'创建时间', 'SCHEMA', 'dbo', 'TABLE', 'users', 'COLUMN', 'created_at';
GO

INSERT INTO dbo.users (username, email) VALUES (N'alice', N'alice@example.com');
INSERT INTO dbo.users (username, email)
VALUES (N'bob', N'bob@example.com');
INSERT INTO dbo.users (username, email)
VALUES (N'charlie', N'charlie@example.com');
GO
