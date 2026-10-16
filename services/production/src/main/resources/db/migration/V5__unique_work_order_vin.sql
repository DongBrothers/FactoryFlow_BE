-- 생산 완료 때 발행하는 VIN 은 차 한 대마다 하나다 (docs/specs/production-completion.md). 작업지시 id 가 일련번호라 겹치지 않지만 DB 로도 막는다
ALTER TABLE work_order ADD UNIQUE KEY uk_work_order_vin (vin);
