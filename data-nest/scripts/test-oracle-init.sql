-- ============================================
-- test-oracle-init.sql
-- Oracle 23ai Free 启动后自动跑（/container-entrypoint-initdb.d/）
-- 用途：建 testuser 账号 + demo 表 + 种子数据，给 ConnectionTest / JdbcPreview / Addax 演示
-- ============================================

-- 1. 解锁 testuser（默认 APP_USER 创建后是锁定状态，强制要求改密码）
ALTER
USER testuser IDENTIFIED BY testpass123 ACCOUNT UNLOCK;

-- 2. 给 testuser 授权
GRANT CONNECT, RESOURCE, DBA TO testuser;
GRANT
CREATE VIEW TO testuser;
GRANT
CREATE SEQUENCE TO testuser;
ALTER
USER testuser QUOTA UNLIMITED ON USERS;

-- 3. demo 表
BEGIN
EXECUTE IMMEDIATE 'DROP TABLE test_orders';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/

CREATE TABLE test_orders
(
    id         NUMBER(19) PRIMARY KEY,
    order_no   VARCHAR2(50) NOT NULL,
    customer   VARCHAR2(100) NOT NULL,
    amount     NUMBER(10,2) NOT NULL,
    status     VARCHAR2(20) DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_test_orders_status ON test_orders (status);
CREATE INDEX idx_test_orders_created_at ON test_orders (created_at);

-- 4. 100 行种子数据
BEGIN
FOR i IN 1..100 LOOP
        INSERT INTO test_orders (id, order_no, customer, amount, status, created_at)
        VALUES (
            i,
            'ORD' || LPAD(i, 8, '0'),
            'customer_' || i,
            ROUND(DBMS_RANDOM.VALUE(10, 5000), 2),
            CASE MOD(i, 4)
                WHEN 0 THEN 'PENDING'
                WHEN 1 THEN 'PAID'
                WHEN 2 THEN 'SHIPPED'
                ELSE 'COMPLETED'
            END,
            SYSTIMESTAMP - NUMTODSINTERVAL(i, 'DAY')
        );
END LOOP;
COMMIT;
END;
/

-- 5. 验证
SELECT 'test_orders count: ' || COUNT(*)
FROM test_orders;
