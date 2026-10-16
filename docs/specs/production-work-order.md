# MES 작업지시·공정·실적 (inventory.reserved 트리거 + 혼합 자동화: 로봇 공정 배정·설비 보고 + 작업자 API)

## 목적
기획서(docs/planning/production-flow.md)와 주문 이행 SAGA(docs/specs/saga-order-fulfillment.md) 성공 흐름 6~7번이다.
부품 예약이 끝나면(`inventory.reserved`) production 이 차량 1대(생산 건)마다 작업지시 1건과 공정 5개를 만든다.
공정 진행은 혼합 자동화다 (기획서 "정한 것"):
- 로봇 공정(PRESS, BODY, PAINT): production 의 배정 스케줄러가 비어 있는 설비에 차를 배정하고, 설비가 시작·완료·양품/불량을 보고한다
- 사람 공정(ASSEMBLY, INSPECTION): 라인 작업자가 시작·종료하며 양품·불량 실적을 입력한다. 작업자 API 는 로봇 공정의 수동 개입에도 쓴다

이 작업은 production 쪽(배정, 보고 수신, 작업자 API)까지다. 배정을 받아 일하는 가상 설비(simulator)는 다음 작업이고, 그 전까지는 작업자 API 로 로봇 공정도 진행할 수 있다.

## 흐름

### 1. 작업지시 생성 (inventory.reserved 수신)
1. production 이 `inventory.reserved { productionId }` 를 받는다 (기존 리스너 `InventoryEventListener.onReserved`)
2. 생산 건이 PLANNED, WAITING_MATERIAL 이면 READY 로 바꾸고, 같은 트랜잭션에서
   - `work_order` 1건 (status = RELEASED)
   - `work_order_step` 5건, 라우팅 순서: PRESS(1) → BODY(2) → PAINT(3) → ASSEMBLY(4) → INSPECTION(5), 모두 PENDING
   - `production.status-changed`(READY) (기존과 같음)
3. 그 밖의 상태는 지금 규칙 그대로다 (docs/specs/production-reservation-result.md): 끝난 생산 건(CANCELLED, FAILED)은 `production.cancelled` 재발행, 나머지는 무시

### 2. 작업자 API (사람 공정, 로봇 공정 수동 개입)
밖 주소는 `/api/production/**`, gateway 가 `/api` 를 떼고 넘기므로 컨트롤러는 `/production/**` 이다. 작업자는 로그인한 직원이고, gateway 가 넣는 `X-User-Id`(JWT sub, public_id)를 실적의 작업자로 남긴다.

| API | 하는 일 |
|---|---|
| `GET /production/work-orders?status=RELEASED&lastId=0&size=20` | 작업지시 목록. status 는 RELEASED, IN_PROGRESS. id 커서(`CursorPage`, size 1~100). 항목: workOrderId, productionId, orderId, productId, status, 현재 공정 |
| `GET /production/work-orders/{workOrderId}` | 작업지시 상세: 공정 5개(상태, 시작·종료 시각)와 공정별 실적 |
| `POST /production/work-orders/{workOrderId}/steps/{process}/start` | 공정 시작. 본문 `{ "equipmentCode": "PRESS-01" }` (이 공정을 하는 설비) |
| `GET /production/equipments?process=PRESS` | 공정의 설비 목록과 상태 (작업자가 설비를 고를 때) |
| `POST /production/work-orders/{workOrderId}/steps/{process}/complete` | 공정 종료 + 실적. 본문 `{ "result": "GOOD" \| "DEFECT", "defectReason": "도장 얼룩" }` |

**시작**
- 앞 공정이 모두 DONE 이고, 이 공정이 PENDING 일 때만 시작한다. 한 작업지시에서 IN_PROGRESS 공정은 하나뿐이다
- 지정한 설비는 이 공정의 설비여야 하고 RUNNING 이어야 한다. 공정에 설비를 기록한다 (고장 나면 이 설비에서 진행 중인 차량만 실패, #74)
- 공정 → IN_PROGRESS (started_at, equipment_code)
- 작업지시의 첫 시작이면 (보통은 프레스 배정 때, 수동 개입이면 작업자의 프레스 시작 때) 작업지시 IN_PROGRESS, 생산 건 IN_PROGRESS(started_at) + `production.status-changed`(IN_PROGRESS). 이 순간부터 취소할 수 없다 (기획서 "취소 기준"). 불량 재작업으로 다시 시작할 때는 다시 일어나지 않는다

**종료**
- IN_PROGRESS 공정만 종료한다. `production_result` 1줄 (result, defectReason, 작업자, 공정 시작·종료 시각)
- GOOD → 공정 DONE (ended_at). 다음 공정을 시작할 수 있다
- DEFECT → 실적만 남기고 공정은 PENDING 으로 돌아간다 (같은 공정 재작업). 이벤트는 없다
- 검사(INSPECTION) GOOD 이면 공정 DONE 에 이어 VIN 발행, 작업지시·생산 건 COMPLETED, `production.completed` 까지 같은 트랜잭션에서 한다 (docs/specs/production-completion.md)

### 3. 로봇 공정 자동 진행 (배정 스케줄러 + 설비 보고)
**배정** (production 스케줄러, `factoryflow.line.dispatch-interval` 기본 5초마다. `factoryflow.line.dispatch-enabled` 로 켜고 끈다. 배정을 받을 simulator 가 생길 때까지는 꺼 둔다)
1. 로봇 공정(PRESS, BODY, PAINT)마다 비어 있는 설비를 찾는다: RUNNING 이고 맡은 공정이 없는(occupied_step_id 가 비어 있는) 설비
2. 그 공정이 PENDING 이고 앞 공정이 모두 DONE 인 작업지시를, 비어 있는 설비 수만큼 작업지시 id 순서(먼저 발행된 차)로 고른다. 작업지시는 RELEASED 또는 IN_PROGRESS
   - PRESS 도 발행되자마자 고른다 (기다리지 않는다). 그래서 부품이 다 잡힌 차는 다음 배정 주기에 프레스가 배정되어 취소할 수 없게 된다
   - 다 읽지 않고 필요한 개수만 DB 에서 고른다
3. 한 차씩 따로 트랜잭션으로 배정한다: 공정 DISPATCHED(설비 기록) + `production.step-dispatched { workOrderId, process, equipmentCode }`. 작업지시의 첫 시작이면 작업지시·생산 건 IN_PROGRESS + status-changed (취소 불가)
   - 배정하려는 순간 상태가 바뀌었으면(작업자가 먼저 시작 등) 그 차는 이번 주기에서 빼고 다음 후보로 그 설비를 채운다. 설비 쪽 이유(정지, 다른 차가 먼저 잡음)면 그 설비만 빼고 같은 차로 다음 설비를 시도한다 (먼저 발행된 차가 밀리지 않게). 계속 실패하는 차가 뒤의 차를 막지 않는다. 뺀 차는 다음 주기에 다시 본다

**설비 보고** (simulator → production, ProcessedEventGuard)
| 받은 이벤트 | 조건 | 처리 |
|---|---|---|
| `equipment.step-started` | 그 공정이 DISPATCHED 이고 같은 설비 | IN_PROGRESS (started_at) |
| `equipment.step-completed` GOOD | 그 공정이 IN_PROGRESS 이고 같은 설비 | 실적 GOOD (작업자 없음, 설비 기록) + DONE |
| `equipment.step-completed` DEFECT | 같음 | 실적 DEFECT(사유) + PENDING → 다음 주기에 다시 배정 (재작업) |
| 그 밖 (상태·설비가 다름, 끝난 작업지시) | | 무시 (INFO 로그). 순서가 꼬였거나 수동 개입으로 이미 바뀐 경우다 |

## 규칙 / 예외
| 상황 | 처리 |
|---|---|
| status, process, result 가 정해진 값이 아님, lastId 음수, size 1~100 밖, id 가 양수가 아님, X-User-Id 없음 | 400 (Bean Validation, 공통 핸들러) |
| result 가 DEFECT 인데 defectReason 이 비었음 | 400 (요청 DTO 커스텀 제약) |
| 없는 작업지시 | 404 `WORK_ORDER_NOT_FOUND` |
| 없는 설비 | 404 `EQUIPMENT_NOT_FOUND` |
| 다른 공정의 설비를 지정 (예: PRESS 에 BODY-01) | 409 `EQUIPMENT_PROCESS_MISMATCH` |
| 설비가 RUNNING 이 아님 (정지·고장) | 409 `EQUIPMENT_NOT_RUNNING` |
| 설비가 다른 차를 작업 중 (맡은 공정이 있음) | 409 `EQUIPMENT_BUSY` |
| 두 차가 같은 설비를 동시에 잡음 (작업자끼리, 배정과 작업자) | 설비 `@Version` 으로 한쪽만 반영. 늦은 쪽 API 는 409 `PRODUCTION_CONFLICT`, 배정은 건너뛰고 다음 주기에 |
| 앞 공정이 DONE 이 아닌데 시작 | 409 `STEP_OUT_OF_ORDER` |
| PENDING 이 아닌 공정 시작, IN_PROGRESS 가 아닌 공정 종료 | 409 `INVALID_STEP_STATUS` |
| 작업지시가 CANCELLED, FAILED | 409 `WORK_ORDER_CLOSED` |
| 같은 생산 건·작업지시를 동시에 바꿈 (공정 시작 vs 취소 수신 등) | 생산 건과 작업지시에 `@Version`. 늦은 쪽 API 는 409 `PRODUCTION_CONFLICT`, 리스너는 재시도 |
| 작업자가 DISPATCHED 공정을 시작·종료하려 함 | 409 `INVALID_STEP_STATUS` (설비 보고를 기다린다) |
| 배정과 작업자 시작이 동시에 같은 작업지시를 바꿈 | 작업지시 version 으로 한쪽만 반영. 배정 쪽은 건너뛰고 다음 주기에 다시 본다 |
| 같은 생산 건에 inventory.reserved 가 두 번 옴 | 작업지시는 하나뿐이다 (`work_order.production_id` UNIQUE). 두 번째는 READY 가 아니라서 지금 규칙대로 무시된다 |

- 설비 테이블과 "RUNNING 일 때만 시작" 조건은 이 작업에서 만든다. 설비 상태를 바꾸는 `equipment.status-changed` 수신과 고장 시 실패 처리는 #74 다. 그 전까지 설비는 모두 RUNNING 이다
- 이 작업에서 새로 발행하는 이벤트는 없다 (status-changed 의 READY, IN_PROGRESS 시점만 정해진다)
- 목록은 한 번에 다 읽지 않는다 (SAGA "목록 조회"). 공통 `CursorPage`(common-web)를 둔다: items, count, last, nextCursor

## 테이블 (Flyway)
| 테이블 | 컬럼 |
|---|---|
| `production` | `version BIGINT NOT NULL DEFAULT 0` 추가 |
| `equipment` | id, code (UNIQUE, 예: PRESS-01), name, process, status (RUNNING, STOPPED, FAULT), occupied_step_id (지금 맡은 공정, 배정·시작 때 기록, 양품 완료·불량 재작업 때 비움), version. 공정마다 2대를 기본 데이터로 넣는다: PRESS-01·02, BODY-01·02, PAINT-01·02, ASSEMBLY-01·02, INSPECTION-01·02 (simulator 와 같은 코드) |
| `work_order` | id, production_id (UNIQUE), order_id, product_id, status, vin (NULL, #73), released_at, started_at, completed_at, step_changed_at, version. 공정이 바뀔 때마다 step_changed_at 을 바꿔 작업지시 version 이 오르게 한다 (같은 작업지시의 공정을 동시에 바꾸면 늦은 쪽이 409) |
| `work_order_step` | id, work_order_id, process, seq (1~5), status, equipment_code (시작 때), started_at, ended_at. UNIQUE (work_order_id, process) |
| `production_result` | id, work_order_step_id, result (GOOD, DEFECT), defect_reason, worker_id (작업자 public_id, 설비 보고면 NULL), equipment_code, started_at, ended_at |

## 관련 이벤트 (events.md 참조)
| 이벤트 | 발행 → 수신 | payload |
|---|---|---|
| inventory.reserved | inventory → production | productionId |
| production.step-dispatched | production → simulator | workOrderId, process, equipmentCode |
| equipment.step-started | simulator → production (`production.equipment-step-started`) | equipmentCode, workOrderId, process |
| equipment.step-completed | simulator → production (`production.equipment-step-completed`) | equipmentCode, workOrderId, process, result, defectReason |
| production.status-changed | production → order | orderId, productionId, productId, status |

## 완료 조건 (테스트로 확인할 것)
- [ ] PLANNED / WAITING_MATERIAL 생산 건에 inventory.reserved → READY, 작업지시 1건(RELEASED), 공정 5개가 라우팅 순서로 PENDING
- [ ] 같은 생산 건에 inventory.reserved 가 다시 와도 작업지시는 1건
- [ ] PRESS 시작(PRESS-01) → 공정 IN_PROGRESS·설비 기록, 작업지시·생산 건 IN_PROGRESS, status-changed(IN_PROGRESS) 1건
- [ ] 다른 공정의 설비면 409 EQUIPMENT_PROCESS_MISMATCH, RUNNING 이 아니면 409 EQUIPMENT_NOT_RUNNING, 없는 설비는 404
- [ ] 앞 공정이 DONE 이 아니면 409 STEP_OUT_OF_ORDER, 같은 공정 두 번 시작은 409 INVALID_STEP_STATUS
- [ ] GOOD 종료 → 공정 DONE, 실적 1줄(작업자 = X-User-Id), 다음 공정 시작 가능
- [ ] DEFECT 종료 → 실적 1줄(사유), 공정 PENDING, 다시 시작·종료 가능. 이벤트 없음
- [ ] DEFECT 인데 사유 없음, 잘못된 process / result 는 400
- [ ] 5개 공정을 모두 GOOD 으로 끝내면 공정이 모두 DONE (완료 처리는 production-completion.md)
- [ ] 작업지시 목록은 status 별 id 커서로 나뉘고 last, nextCursor 가 맞다. 상세는 공정과 실적을 보여 준다
- [ ] 동시에 같은 생산 건을 바꾸면 하나만 반영되고 늦은 쪽은 409 PRODUCTION_CONFLICT
- [ ] 없는 작업지시는 404
- [ ] 배정: 앞 공정이 끝난 로봇 공정을 비어 있는 RUNNING 설비에 먼저 발행된 차부터 배정, DISPATCHED + step-dispatched. 바쁜 설비·정지 설비에는 배정하지 않는다
- [ ] 프레스는 작업지시가 발행되자마자 배정되고, 배정 때 작업지시·생산 건 IN_PROGRESS + status-changed
- [ ] step-started → IN_PROGRESS, step-completed GOOD → DONE(실적, 작업자 없음), DEFECT → PENDING 후 다시 배정
- [ ] 상태·설비가 맞지 않는 보고는 무시, 같은 보고 두 번은 한 번만 반영
- [ ] 작업자 API 로 DISPATCHED 공정을 시작하면 409
