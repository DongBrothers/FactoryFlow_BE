# MES 서비스 분리 (production → production + mes)

## 목적
production 이 ERP 의 생산 계획(생산 건, 제품·BOM)과 현장 실행(MES: 설비, 작업지시, 공정, 실적, 배정)을 함께 맡고 있다. 현장 실행을 새 서비스 `mes` 로 나눈다.
- 경계: **production = 무엇을 만들지 (계획)**, **mes = 어떻게 만들고 있는지 (실행)**. 작업지시 발행이 둘 사이의 넘김점이다
- 배정 스케줄러(5초)와 설비 보고처럼 부하 성격이 다른 실행 쪽을 따로 늘리고 배포한다
- 사용자에게 보이는 업무 흐름(주문 → 생산 → 출고, 취소 규칙)은 그대로다. 바뀌는 것은 API 경로, 작업지시 발행 응답, 서비스 사이 이벤트다

AWS 준비는 끝났다 (2026-10-20): ECR `ff-mes`, 로그 그룹 `/ecs/ff-mes`, 태스크 정의 `ff-mes:1`, ECS 서비스 `ff-mes`(desired 0, Service Connect `mes.factoryflow.local:8080`).

## 나누는 것

| | production (남음) | mes (옮김) |
|---|---|---|
| 테이블 | product, bom, production, outbox, processed_event | equipment, equipment_status_history, work_order, work_order_step, production_result, outbox, processed_event |
| 도메인 | Product, Bom, Production, ProductionStatus | Equipment, EquipmentStatus, EquipmentStatusHistory, WorkOrder, WorkOrderStatus, WorkOrderStep, StepStatus, StepResult, ProductionProcess, ProductionResult, Vin |
| 서비스 | ProductService, ProductWriter, ProductionService | WorkOrderService, EquipmentService |
| 받는 곳 | ProductController, InternalProductController, ProductionController(발행 대기·발행), OrderEventListener, InventoryEventListener, 새 MesEventListener | WorkOrderController, EquipmentController, SimulatorEventListener, 새 ProductionEventListener, scheduler/LineDispatchScheduler·LineDispatcher |
| 설정 | client/inventory/PartClient | config/LineConfig·LineProperties (`factoryflow.line.*`) |
| DB | production_db | mes_db (같은 RDS ff-mysql 에 새 DB) |

## 흐름

### 1. 작업지시 발행 (production → mes)
`POST /production/productions/{productionId}/work-order` (PRODUCTION) 는 production 에 남는다.
1. production: 생산 건을 잠그고(`PESSIMISTIC_FORCE_INCREMENT`) READY 이고 아직 발행 안 됨(`released_at` NULL)인지 확인
2. `production.released_at` = 지금 + `production.released { productionId, orderId, productId }` (같은 트랜잭션, Outbox)
3. **응답 202** `{ productionId, releasedAt }`. 작업지시는 mes 가 이벤트를 받아 만들므로 응답에 작업지시 상세가 없다 (화면은 mes 작업지시 목록에서 본다)
4. mes `ProductionEventListener.onReleased` → 작업지시 RELEASED + 라우팅 순 공정 5개 PENDING (지금 `releaseWorkOrder` 그대로). 같은 productionId 의 작업지시가 이미 있으면 건너뜀

- 발행 대기 목록은 "READY 이고 `released_at` NULL" 로 production 안에서만 찾는다 (작업지시 테이블을 보지 않는다)
- 생산 건 상태는 발행해도 READY 그대로다 (고객에게는 계속 접수 RECEIVED)

### 2. 생산 시작 (mes → production)
- mes: 작업지시의 첫 시작(프레스 배정 또는 작업자 시작)에서 `mes.production-started { productionId, workOrderId }`
- production `MesEventListener.onProductionStarted` → READY → IN_PROGRESS + `production.status-changed(IN_PROGRESS)` (order 는 지금처럼 IN_PRODUCTION)
- 이미 IN_PROGRESS·COMPLETED 면 무시 (INFO). CANCELLED 면 WARN 후 무시 (아래 취소 규칙상 생기지 않는다)

### 3. 완성 (mes → production → inventory·order)
- mes: 검사 GOOD → VIN 발급(`Vin.issue`, 지금처럼 제품 id + 작업지시 id) → 작업지시 COMPLETED + `mes.vehicle-completed { productionId, workOrderId, vin }`
- production `MesEventListener.onVehicleCompleted` → COMPLETED + `production.completed { orderId, productionId, productId, vin }` (payload·수신자 그대로: inventory 완성차 재고, order 차량 COMPLETED)
- 큐가 달라 `mes.production-started` 보다 먼저 올 수 있다 → READY 에서도 받는다 (READY → COMPLETED, status-changed 는 보내지 않고 production.completed 만). 늦게 온 started 는 COMPLETED 라 무시

### 4. 주문 취소 (취소 판단이 발행 전후로 나뉜다)
규칙은 지금과 같다: **첫 공정을 시작하기 전이면 취소, 시작했으면 거절.** 발행한 뒤에는 "시작했는가" 를 mes 만 알므로 mes 가 정한다.

production `cancelAll` (order.cancel-requested) 의 생산 건마다
| 생산 건 | 처리 |
|---|---|
| 이미 CANCELLED | production.cancelled 다시 (지금과 같음) |
| PLANNED, WAITING_MATERIAL, 발행 전 READY | CANCELLED + production.cancelled (지금과 같음) |
| **발행한 READY** (`released_at` 있음) | 상태는 그대로 두고 `production.release-cancel-requested { orderId, productionId }` → mes 의 답을 기다린다 |
| IN_PROGRESS, COMPLETED | production.cancel-rejected(ALREADY_STARTED) (지금과 같음) |

mes `ProductionEventListener.onReleaseCancelRequested`
| 작업지시 | 처리 |
|---|---|
| RELEASED (아직 시작 전) | CANCELLED + `mes.work-order-cancelled { productionId }` |
| IN_PROGRESS, COMPLETED | `mes.work-order-cancel-rejected { productionId, status }` |
| 이미 CANCELLED | mes.work-order-cancelled 다시 |
| 아직 없음 (발행 이벤트보다 먼저 옴) | CANCELLED 상태로 작업지시를 만들어 두고 mes.work-order-cancelled. 뒤에 온 production.released 는 "이미 있음" 이라 건너뜀 |

production `MesEventListener`
- `onWorkOrderCancelled` → READY → CANCELLED + production.cancelled (inventory 예약 해제, order 차량 CANCELLED: 지금 보상 그대로)
- `onWorkOrderCancelRejected` → production.cancel-rejected(ALREADY_STARTED, 받은 status 를 생산 건 상태로 바꿔: 작업지시 IN_PROGRESS·COMPLETED → 생산 건 IN_PROGRESS·COMPLETED)

mes 는 취소한 작업지시를 다시 시작하지 않고, 시작한 작업지시는 취소하지 않는다. 그래서 production 이 "시작됨" 과 "취소됨" 을 둘 다 받는 일은 없다.

### 5. 설비 (simulator ↔ mes)
- 배정 이벤트 이름이 `production.step-dispatched` → `mes.step-dispatched` (payload 그대로). simulator 의 수신 큐를 `simulator.mes-step-dispatched` 로 바꾼다
- `equipment.step-started`·`step-completed`·`status-changed` 는 이름 그대로, 받는 서비스가 mes (큐 `mes.equipment-*`)
- 배정·시간 초과·고장 재배정 규칙은 그대로 옮긴다 (`factoryflow.line.*` 설정도 mes 로)
- simulator 는 지금처럼 DB 없이 메모리에 둔다. 설비 흉내라 기록의 주인은 mes 이고, 재시작으로 잃은 작업은 mes 의 시간 초과(시작 보고 1분, 완료 보고 5분)가 되돌려 다시 배정한다 (docs/specs/simulator-restart.md 그대로)

### 6. API 경로 (gateway)
| 지금 | 바뀐 뒤 | 역할 |
|---|---|---|
| `GET /api/production/work-orders`, `/{id}` | `GET /api/mes/work-orders`, `/{id}` | 읽기 역할 |
| `POST /api/production/work-orders/{id}/steps/{process}/start`·`complete` | `POST /api/mes/work-orders/{id}/steps/{process}/start`·`complete` | PRODUCTION |
| `GET /api/production/equipments?process=` | `GET /api/mes/equipments?process=` | 읽기 역할 |
| `POST /api/production/productions/{id}/work-order` | 그대로 (응답 201 → 202) | PRODUCTION |
- gateway 라우트 `Path=/api/mes/**` → `http://mes.factoryflow.local:8080` (dev 시크릿). 로컬은 다른 서비스의 application-local.yml 처럼 빈 포트 하나를 정한다
- `SecurityConfig.authorizeWrites` 의 작업지시 공정 줄 경로를 바꾼다. 서비스 이름 접두사가 바뀌어 프론트도 경로를 바꿔야 한다

## 규칙 / 예외
| 상황 | 처리 |
|---|---|
| 발행: 생산 건 없음 / READY 아님 / 이미 발행 | 404 PRODUCTION_NOT_FOUND / 409 PRODUCTION_NOT_READY / 409 WORK_ORDER_ALREADY_RELEASED (production, 지금과 같음) |
| 발행 직후 작업지시 조회 | 아직 없을 수 있다 (mes 가 이벤트를 받기 전). 404 WORK_ORDER_NOT_FOUND → 화면은 잠시 뒤 다시 조회 |
| 같은 이벤트 두 번 | 각 서비스의 ProcessedEventGuard. 다른 eventId 의 같은 내용은 업무 키(productionId)로 한 번 더 거른다 |
| 발행 이벤트와 취소 요청이 mes 에 순서 바뀌어 도착 | 4번 "아직 없음" 처리 |
| 완성이 시작보다 먼저 도착 (production) | 3번 처리 |
| mes 가 멈춤 | 발행·취소 요청은 큐에 쌓였다가 mes 가 뜨면 처리된다. 발행된 차의 취소 답이 늦어지는 동안 주문은 취소 요청 중으로 보인다 |

에러 코드
- production 에 남음: PRODUCTION_NOT_FOUND, PRODUCTION_NOT_READY, WORK_ORDER_ALREADY_RELEASED, PRODUCT_*, PART_NOT_FOUND, INVENTORY_UNAVAILABLE, INVALID_ORDER_QTY, PRODUCTION_CONFLICT
- mes 로 옮김 (`MesErrorCode`, 코드 이름 그대로): WORK_ORDER_NOT_FOUND, STEP_NOT_FOUND, EQUIPMENT_NOT_FOUND, WORK_ORDER_CLOSED, STEP_OUT_OF_ORDER, INVALID_STEP_STATUS, EQUIPMENT_PROCESS_MISMATCH, EQUIPMENT_NOT_RUNNING, EQUIPMENT_BUSY, VIN_RANGE_EXCEEDED, 충돌은 MES_CONFLICT (지금 PRODUCTION_CONFLICT 를 쓰던 작업지시·설비 충돌)

### 테이블 변경
| 서비스 | 변경 |
|---|---|
| mes (V1~) | equipment, equipment_status_history, work_order, work_order_step, production_result, outbox, processed_event 생성 (production 의 V4~V9 최종 모양 그대로). 설비 10대 시드 |
| production (V10) | `production.released_at` DATETIME(6) NULL 추가. 이미 작업지시가 있는 생산 건은 `work_order.released_at` 으로 채운다 |
| production (V11, #136) | MES 테이블 5개 삭제. mes 가 dev 에서 정상인 것을 본 뒤 (되돌릴 수 있게 한 PR 늦췄다) |

### 데이터 이전 (dev, 한 번)
같은 RDS 인스턴스라 DB 사이 `INSERT … SELECT` 로 옮긴다. id 를 그대로 옮겨 VIN(작업지시 id 포함)이 겹치지 않게 한다.
1. production·simulator 를 멈춘다 (desired 0). 진행 중인 배정은 mes 의 시간 초과가 되돌린다
2. mes_db 생성 + GRANT → ff-mes desired 1 로 Flyway 가 테이블을 만든 뒤 다시 0
3. 이전 SQL: mes_db 의 시드 설비를 지우고 production_db 의 5개 테이블을 id 그대로 복사 (CloudShell VPC 환경에서 사람이 실행, 스크립트는 구현 PR 에 둔다)
4. ff-mes desired 1 (큐를 먼저 만들어야 production 의 새 이벤트가 버려지지 않는다) → production 배포 (V10) → simulator 배포 → simulator desired 1(쓸 때)
- production outbox 에 남은 `production.step-dispatched` 는 받는 큐가 없어 버려진다. 그 공정은 mes 의 시작 보고 시간 초과(1분)로 되돌아가 다시 배정된다

## 관련 이벤트 (events.md 참조, 사람만 수정)
| 이벤트 | 발행 → 수신 | payload | 변경 |
|---|---|---|---|
| production.released | production → mes | productionId, orderId, productId | 새로 |
| production.release-cancel-requested | production → mes | orderId, productionId | 새로 |
| mes.production-started | mes → production | productionId, workOrderId | 새로 |
| mes.vehicle-completed | mes → production | productionId, workOrderId, vin | 새로 |
| mes.work-order-cancelled | mes → production | productionId | 새로 |
| mes.work-order-cancel-rejected | mes → production | productionId, status | 새로 |
| mes.step-dispatched | mes → simulator | workOrderId, process, equipmentCode, dispatchSeq | production.step-dispatched 를 대신함 (이름 변경 = 새 이벤트) |
| equipment.step-started, step-completed, status-changed | simulator → **mes** | 그대로 | 수신자 변경 |
| production.completed, status-changed, cancelled, cancel-rejected | production → 그대로 | 그대로 | 없음 (보내는 때만 mes 이벤트를 받은 뒤로 바뀜) |
| production.step-dispatched | — | — | 없어짐 |

events.md 흐름 설명도 바꾼다: "프레스·차체·도장은 mes 가 설비에 배정", "취소는 발행 전엔 production, 발행 뒤엔 mes 가 판단".

## 함께 고칠 명세
- work-order-release.md: 발행 응답 202, `released_at`, 발행 대기 목록 조건
- production-work-order.md, production-completion.md, equipment-status.md: 주체가 mes, 경로 /api/mes/**, 생산 시작·완성 알림 이벤트
- order-cancel.md, saga-order-fulfillment.md: 발행 뒤 취소는 mes 가 판단, 성공 흐름의 서비스 이름
- simulator.md, simulator-restart.md: 배정 이벤트 mes.step-dispatched, 보고 받는 곳 mes
- role-permissions.md, list-apis.md: 작업지시·설비 경로

## 사람만 수정하는 파일 (구현 때 적용 스크립트로 넘긴다)
- `settings.gradle`: `include 'services:mes'`
- `docs/specs/events.md`: 위 표
- `.github/workflows/cd.yml`: `ALL` 에 `mes`
- `common/common-test`: 필요하면 mes 를 위한 규칙 예외 (지금 규칙 그대로면 없음)
- GitHub 시크릿 `APP_DEV_YML_MES` 새로, `APP_DEV_YML_GATEWAY` 에 mes 라우트

## 테스트를 옮기는 것 (지우지 않는다)
production 테스트 중 MES 쪽은 패키지만 바꿔 mes 로 옮긴다: WorkOrderControllerTest, EquipmentControllerTest, LineDispatchTest, LineDispatcherTest, LinePropertiesTest, VinTest, EquipmentStatusTest. ProductionCompletionTest·WorkOrderReleaseTest·OrderCancelRequestedTest 는 서비스 경계에서 둘로 나눈다 (production 쪽은 이벤트 수신·발행, mes 쪽은 공정·VIN). 나누면서 바뀌는 단언은 PR 본문에 테스트별로 적는다.

## 완료 조건 (테스트로 확인할 것)
production
- [ ] 발행: released_at + production.released, 202. READY 아님 409, 이미 발행 409, 없음 404
- [ ] 발행 대기 목록: READY 이고 released_at NULL
- [ ] mes.production-started → IN_PROGRESS + status-changed. 중복·늦은 것 무시
- [ ] mes.vehicle-completed → COMPLETED + production.completed. READY 에서 와도 처리
- [ ] 취소: 발행 전은 지금처럼, 발행 뒤는 release-cancel-requested 만, IN_PROGRESS 이후 거절
- [ ] mes.work-order-cancelled → CANCELLED + production.cancelled, cancel-rejected → production.cancel-rejected
- [ ] V10 마이그레이션 (released_at, 기존 작업지시로 채움)
mes
- [ ] production.released → 작업지시 + 공정 5개, 같은 생산 건 두 번이면 하나
- [ ] 첫 시작(배정·작업자)에서 mes.production-started 한 번 (불량 재작업 재시작은 안 보냄)
- [ ] 검사 GOOD → VIN + mes.vehicle-completed
- [ ] 취소 요청: 시작 전 취소, 시작 후 거절, 발행보다 먼저 온 취소
- [ ] 옮긴 테스트 모두 통과 (배정, 시간 초과, 고장 재배정, 작업자 공정, Swagger)
- [ ] mes.step-dispatched 발행, equipment.* 수신
- [ ] V1 마이그레이션, MigrationTest, ArchitectureTest
simulator
- [ ] mes.step-dispatched 수신
gateway
- [ ] /api/mes/** 라우팅, 공정 시작·종료는 PRODUCTION 만, 조회는 읽기 역할
