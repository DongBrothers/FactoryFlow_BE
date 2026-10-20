# MES 분리 배포 (dev, 한 번)
명세: docs/specs/mes-split.md "데이터 이전". 같은 RDS(ff-mysql) 안에서 production_db 의 MES 테이블을 mes_db 로 id 그대로 옮긴다 (VIN 에 작업지시 id 가 들어가 겹치지 않게).
AWS 준비(ECR ff-mes, 로그 그룹 /ecs/ff-mes, 태스크 정의 ff-mes, ECS 서비스 ff-mes desired 0)는 끝나 있다.

## 0. 미리 (PR 머지 전)
- GitHub 시크릿 APP_DEV_YML_MES 등록 (production 것에서 DB 이름만 mes_db, flyway seed-dev·clients.inventory 없음)
- GitHub 시크릿 APP_DEV_YML_GATEWAY 에 라우트 추가: id mes, uri http://mes.factoryflow.local:8080, Path=/api/mes/** (다른 라우트와 같은 필터)
- RDS 에 DB 와 권한 (CloudShell VPC 환경: 서브넷 subnet-0659fd37622a2a142, 보안 그룹 sg-0535230f2f50fab11, 마스터 계정)
  ```sql
  CREATE DATABASE IF NOT EXISTS mes_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
  GRANT ALL PRIVILEGES ON mes_db.* TO '<앱 계정>'@'%';
  ```

## 1. 멈춤
- ff-production, ff-simulator desired 0 (진행 중인 배정은 mes 의 시작 보고 시간 초과(1분)가 되돌린다)

## 2. PR 머지 → CD
- mes 가 처음 배포된다. CD 는 desired 를 바꾸지 않으므로 ff-mes 는 아직 0
- ff-mes desired 1 → 로그에서 Flyway V1·V2 적용 확인 → desired 0

## 3. 데이터 옮기기 (CloudShell VPC 환경, 마스터 계정)
```sql
-- mes 시드 설비를 지우고 production 의 설비 상태를 그대로 가져온다
DELETE FROM mes_db.equipment;
INSERT INTO mes_db.equipment SELECT id, code, name, process, status, occupied_step_id, version, created_at, updated_at, is_deleted FROM production_db.equipment;
INSERT INTO mes_db.equipment_status_history SELECT id, equipment_code, from_status, to_status, changed_at, created_at FROM production_db.equipment_status_history;
INSERT INTO mes_db.work_order SELECT id, production_id, order_id, product_id, status, vin, released_at, started_at, completed_at, step_changed_at, version, created_at, updated_at FROM production_db.work_order;
INSERT INTO mes_db.work_order_step SELECT id, work_order_id, process, seq, status, equipment_code, dispatched_at, dispatch_seq, started_at, ended_at, created_at, updated_at FROM production_db.work_order_step;
INSERT INTO mes_db.production_result SELECT id, work_order_step_id, result, defect_reason, worker_id, equipment_code, started_at, ended_at, created_at FROM production_db.production_result;
-- 확인: 두 DB 의 행 수가 같아야 한다
SELECT (SELECT COUNT(*) FROM production_db.work_order) p, (SELECT COUNT(*) FROM mes_db.work_order) m;
```
id 를 그대로 넣으면 AUTO_INCREMENT 는 가장 큰 id 다음으로 맞춰진다.

## 4. 다시 켬 (순서 중요)
1. ff-mes desired 1 (mes 의 큐가 먼저 생겨야 production 의 새 이벤트가 버려지지 않는다)
2. ff-production desired 1 (V10: released_at 을 기존 작업지시로 채움)
3. ff-simulator desired 1 (쓸 때만)

## 5. 옛 큐 지우기 (RabbitMQ 관리 화면)
더 이상 읽는 곳이 없다. 특히 production.equipment-* 는 설비 이벤트가 계속 쌓인다
- production.equipment-step-started (+ .dlq)
- production.equipment-step-completed (+ .dlq)
- production.equipment-status-changed (+ .dlq)
- simulator.production-step-dispatched (+ .dlq)

## 6. 확인
- 주문 한 건 → 발행(202) → GET /api/mes/work-orders 에 작업지시 → 공정 진행 → 완성 → 출고
- /ecs/ff-mes 로그 ERROR 없음

## 롤백
- production·mes·simulator 를 이전 태스크 정의로 되돌리고 ff-mes desired 0. production_db 의 MES 테이블은 지우지 않았으므로(다음 PR) 데이터가 남아 있다
- 2 이후 mes 에서 생긴 작업지시·공정 변경은 production_db 에 없다 (dev 라 버린다)
