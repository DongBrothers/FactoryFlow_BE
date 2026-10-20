-- MES 테이블 삭제 (docs/specs/mes-split.md, #136). 작업지시·공정·실적·설비는 mes 서비스(mes_db)가 갖는다
-- production 코드는 V10 부터 이 테이블을 쓰지 않는다. 발행 여부는 production.released_at 으로 안다
DROP TABLE production_result;
DROP TABLE work_order_step;
DROP TABLE work_order;
DROP TABLE equipment_status_history;
DROP TABLE equipment;
