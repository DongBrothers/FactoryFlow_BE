# 쓰지 않는 테이블·컬럼 정리 (#123)

## 목적
코드가 읽지도 쓰지도 않는 테이블과 컬럼, 쓰기만 하고 아무도 읽지 않는 컬럼, 다른 값으로 알 수 있는 중복 컬럼을 지운다. 스키마를 읽는 사람이 쓰이지 않는 값을 실제 동작으로 오해하지 않게 하고, 같은 뜻의 값이 서로 어긋날 수 있는 곳을 줄인다.

## 흐름
1. 서비스마다 새 마이그레이션 하나로 지운다 (이미 적용된 마이그레이션은 체크섬 때문에 고치지 않는다)
2. 엔티티에서 그 필드를 뺀다 (`ddl-auto: validate` 라 엔티티와 테이블이 맞아야 뜬다)
3. 응답에서 빠지는 필드는 명세(purchase-receiving.md, purchase-manual.md)도 고친다

## 규칙 / 예외

### 지우는 것
| 서비스 (마이그레이션) | 대상 | 이유 |
|---|---|---|
| auth (V4) | `customer` 테이블 | 엔티티·레포지토리·API 가 없다. order 의 customer_id 도 이 테이블을 보지 않는다. 고객 데이터가 필요해지면 order 에 둔다 |
| auth (V4) | `user_account.is_deleted` | 계정을 막는 것은 status DISABLED 가 한다 |
| order (V8) | `order_item.fail_reason` | FAILED 상태가 있던 때의 컬럼. 읽고 쓰는 곳이 없다 |
| order (V8) | `customer_order`, `order_item`, `order_vehicle` 의 `is_deleted` | 업무 데이터. 앱이 지우지 않는다 |
| production (V9) | `production.started_at`, `completed_at` | 작업지시(`work_order`)의 같은 시각과 중복. 읽는 곳이 없다. 작업지시 쪽을 남긴다 |
| production (V9) | `production`, `work_order`, `work_order_step`, `production_result` 의 `is_deleted` | 업무 데이터 |
| production (V9) | `production_result.updated_at` | 실적은 고치지 않는다 (모든 컬럼 updatable=false). 다른 이력 테이블처럼 created_at 만 둔다 |
| inventory (V7) | `part_reservation`, `finished_vehicle`, `part_lot` 의 `is_deleted` | 업무 데이터 |
| inventory (V7) | `part_lot_usage.part_id`, `production_id` | part_id 는 lot_id 의 LOT 이, production_id 는 vin 의 완성차가 갖고 있다. 조회는 vin, lot_id 로만 한다 |
| purchase (V5) | `purchase.requested_by` | V2 에서 지운 admin 을 가리키는 컬럼. 발주한 직원은 ordered_by 가 갖는다 |
| purchase (V5) | `purchase.source` | 자동 구매를 없앤 뒤(#114) 항상 MANUAL. 옛 자동 요청은 status CANCELLED 로 끝나 있다. 구매 응답의 `source` 도 뺀다 |
| purchase (V5) | `purchase.is_deleted` | 업무 데이터 |

### 소프트 삭제를 남기는 것
사람이 SQL 로 지울 수 있는 기준 데이터만 `is_deleted` 를 둔다: production `product`, `bom`, `equipment`, inventory `part`, purchase `supplier`. 이 테이블은 지금처럼 삭제된 행을 조회에서 빼고, 삭제된 제품·BOM·부품을 처리하는 흐름(13 S8, S18)도 그대로다.

엔티티 공통 부분을 둘로 나눈다 (common-web)
- `BaseTimeEntity`: created_at, updated_at
- `BaseEntity extends BaseTimeEntity`: + is_deleted (기준 데이터)

### 검토했지만 남기는 것
- `work_order.step_changed_at`: 공정 행에 version 이 없어 공정이 바뀔 때 작업지시 version 을 올리려고 둔다. `OPTIMISTIC_FORCE_INCREMENT` 는 version 을 커밋할 때 올리므로, 작업자 API 가 flush 로 충돌을 확인해 409 로 바꾸는 흐름(`flushOrConflict`)이 깨진다
- `work_order_step.seq`: 공정 순서로 정렬(`OrderBySeqAsc`)하고 앞 공정을 조인(배정 조회)하는 데 쓴다. process 는 문자열이라 DB 에서 대신 정렬할 수 없다
- `part.reserved`, `customer_order.status`·`order_item.status`, `order_vehicle.order_id`, `finished_vehicle.order_id`·`product_id`, 스냅샷 컬럼: 계산할 수 있거나 중복이지만 조회·필터·이벤트에 필요하다

### 바뀌는 동작
- 구매 응답(`POST /purchases`, `GET /purchases`, `POST /purchases/{id}/receive`)에 `source` 가 없다
- 그 밖의 API·이벤트는 그대로다. 업무 데이터는 원래 지워지지 않았으므로 "삭제되지 않은 것만" 조건이 빠져도 결과가 같다

## 관련 이벤트 (events.md 참조)
없음 (payload 바뀌지 않음)

## 완료 조건 (테스트로 확인할 것)
- [ ] 각 서비스 MigrationTest: 마지막 버전, 지운 테이블·컬럼이 없고 남기는 컬럼(기준 데이터의 is_deleted, work_order 의 시각)은 있음
- [ ] purchase: V5 전에 있던 옛 자동 요청·직접 구매 행이 V5 뒤에도 남아 있음
- [ ] inventory: V7 전의 LOT 사용 기록이 V7 뒤에도 vin·lot_id·qty 로 남아 있고, 차량별 LOT 조회가 LOT 의 부품으로 응답함
- [ ] 구매 응답에 source 없음
- [ ] ./gradlew check 통과
