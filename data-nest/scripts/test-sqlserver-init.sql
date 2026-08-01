-- ============================================
-- test-sqlserver-init.sql
-- SQL Server 2022 启动后自动跑（/docker-entrypoint-initdb.d/）
-- 用途：建 demo 数据库 + demo 表 + 种子数据
-- ============================================

-- 1. 建专用测试库（避免跟 master 混）
IF
NOT EXISTS (SELECT * FROM sys.databases WHERE name = 'datanest_test')
BEGIN
    CREATE
DATABASE datanest_test;
END;
GO

USE datanest_test;
GO

-- 2. demo 表
IF OBJECT_ID('test_orders', 'U') IS NOT NULL
DROP TABLE test_orders;
GO

CREATE TABLE test_orders
(
    id         BIGINT IDENTITY(1,1) PRIMARY KEY,
    order_no   VARCHAR(50)    NOT NULL,
    customer   VARCHAR(100)   NOT NULL,
    amount     DECIMAL(10, 2) NOT NULL,
    status     VARCHAR(20) DEFAULT 'PENDING',
    created_at DATETIME2   DEFAULT GETDATE()
);

CREATE INDEX idx_test_orders_status ON test_orders (status);
CREATE INDEX idx_test_orders_created_at ON test_orders (created_at);
GO

-- 3. 100 行种子数据
SET NOCOUNT ON;
DECLARE
@i INT = 1;
WHILE
@i <= 100
BEGIN
INSERT INTO test_orders (order_no, customer, amount, status, created_at)
VALUES ('ORD' + RIGHT ('00000000' + CAST (@i AS VARCHAR (8)), 8),
        'customer_' + CAST(@i AS VARCHAR(10)),
        CAST(10 + (RAND() * 4990) AS DECIMAL(10, 2)),
        CASE (@i % 4)
            WHEN 0 THEN 'PENDING'
            WHEN 1 THEN 'PAID'
            WHEN 2 THEN 'SHIPPED'
            ELSE 'COMPLETED'
            END,
        DATEADD(DAY, -@i, GETDATE()));
SET
@i = @i + 1;
END;
GO

-- 4. 验证
SELECT 'test_orders count: ' + CAST(COUNT(*) AS VARCHAR(10))
FROM test_orders;
GO
