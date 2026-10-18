-- 고객 (docs/specs/customer.md, #125). 주문의 customer_id 가 등록된 고객인지 확인한다. 업무 데이터라 소프트 삭제는 두지 않는다
-- 이미 있는 주문의 customer_id 와 FK 는 걸지 않는다 (논리 참조)
CREATE TABLE customer (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(50)  NOT NULL,
    contact     VARCHAR(100) NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
