# 설비 상태 수신과 고장·정지 시 재배정 (equipment.status-changed)

## 목적
주문 이행 SAGA(docs/specs/saga-order-fulfillment.md) "3. 설비 상태" 다 (#93).
mes 가 `equipment.status-changed` 를 받아 설비 상태를 바꾸고 이력을 남긴다 (OEE 데이터).
고장(FAULT)이나 정지(STOPPED)가 되면 **그 설비에** 배정되었거나(DISPATCHED) 진행 중(IN_PROGRESS)인 공정을 "시작 전"(PENDING)으로 되돌리고 설비를 비운다.
배정 스케줄러(로봇 공정)나 작업자(사람 공정)가 같은 공정의 다른 설비로 이어서 진행하고, 다른 설비가 없으면 고쳐질 때까지 기다린다.

설비 고장은 공장 사정이다. 실제 공장도 차를 버리지 않고 다른 설비로 돌리거나 고친 뒤 이어서 만든다. 그래서 **생산 실패는 없다** (2026-10-16 결정):
생산 건·작업지시·주문 상태와 부품 예약은 그대로이고, 고객에게는 계속 "생산 중" 으로 보인다 (늦어질 뿐).
이전 설계의 `production.failed`, 생산 건·작업지시·주문의 FAILED 상태는 없앤다.

보내는 쪽(simulator)은 #74 에서 만든다. 받는 쪽을 먼저 만든다 (설비 보고 수신과 같은 방식).

## 흐름
1. simulator 가 설비 상태가 바뀔 때마다 `equipment.status-changed { equipmentCode, process, status }` 를 보낸다 (status: RUNNING, STOPPED, FAULT)
2. mes 가 큐 `mes.equipment-status-changed` 로 받는다. `ProcessedEventGuard` 로 같은 이벤트를 한 번만 처리하고, 아래는 처리 기록과 같은 트랜잭션이다
3. 설비를 코드로 찾는다. 없으면 처리 오류 `EQUIPMENT_NOT_FOUND` → 재시도 → DLQ
4. 상태가 지금과 같으면 아무것도 하지 않는다 (INFO 로그)
5. 상태를 바꾸고 이력 1줄: `equipment_status_history { equipment_code, from_status, to_status, changed_at }`. changed_at 은 이벤트가 생긴 시각(envelope.occurredAt)
6. 새 상태가 FAULT 또는 STOPPED 이고 설비가 맡은 공정이 있으면(occupied_step_id) 그 공정을 되돌린다
   - 공정: DISPATCHED·IN_PROGRESS → PENDING (설비 코드·시작 시각 지움). 실적은 남기지 않는다 (끝난 게 아니라 중단됐다)
   - 설비: 맡은 공정 비움
   - 작업지시: 공정 변경 기록(step_changed_at, version). 상태는 그대로
   - 생산 건·주문: 그대로. 이벤트를 보내지 않는다
7. 되돌린 공정은 다음 배정 주기에 같은 공정의 다른 RUNNING 설비로 다시 배정된다 (로봇 공정). 사람 공정은 작업자가 다른 설비로 다시 시작한다

## 설비 상태 이력 조회 — `GET /mes/equipments/{equipmentCode}/history?lastId=&size=20`
gateway `/api/mes/equipments/{equipmentCode}/history`. 설비 현황 화면의 이력 패널 (docs/handoff/frontend-api-gaps.md 3번). 위 5번에서 쌓은 이력을 읽기만 한다.
응답은 다른 API 와 같은 `ApiResponse { data, status, message }` 이고, `data` 가 `CursorPage` 다 (아래는 `data` 안쪽).
```json
{ "items": [ { "fromStatus": "RUNNING", "toStatus": "FAULT", "changedAt": "2026-10-24T10:00:00" } ],
  "count": 1, "last": false, "nextCursor": 12 }
```
- **최신 먼저** (이력 id 내림차순 = mes 가 기록한 순서의 역순). 이력은 추가만 하므로 id 순이 기록 순이다.
  changedAt(이벤트 시각)은 보통 같은 순서지만, 이벤트가 늦게 오면 어긋날 수 있다 (그래도 id 순으로 준다. 커서가 id 라서)
- id 커서(`CursorPage`)를 내림차순으로 쓴다: 처음엔 lastId 없이(가장 최신부터), 다음 페이지는 응답의 nextCursor 를 lastId 로 넘긴다 → `id < lastId`.
  last = true 면 nextCursor 는 null. 주문·완성차 목록(docs/specs/list-apis.md)처럼 최신 먼저이고, 이력에는 밖으로 낼 다른 키가 없어 이력 id 를 커서로 쓴다
- 항목에 id·설비 코드는 넣지 않는다 (경로에 있고, 커서는 nextCursor 로 준다)
- 삭제된 설비(is_deleted)는 없는 설비와 같다 (404)

| 확인 | 실패 |
|---|---|
| equipmentCode 형식 `{공정}-{두 자리}` (`EquipmentController.EQUIPMENT_CODE`, 센서값 조회와 같음), lastId 양수, size 1~100 | 400 (Bean Validation, 공통 핸들러) |
| 설비 있음 (삭제되지 않음) | 404 `EQUIPMENT_NOT_FOUND` |

- 이력이 없으면 200, 빈 목록(last = true)
- lastId 가 다른 설비의 이력 id 여도 오류가 아니다 (그 id 보다 작은 이 설비 이력부터)
- gateway 규칙 추가 없음: GET 이라 `READ` (docs/specs/role-permissions.md)
- 인덱스는 지금 것(`equipment_code, changed_at`)으로 둔다. 설비 상태는 드물게 바뀌어 설비당 이력이 적다 (설비 코드로 거른 뒤 id 정렬). 많아지면 `(equipment_code, id)` 인덱스를 더한다

## 설비 목록 (mes·simulator 기준)
mes 의 기본 설비 데이터(V2)와 simulator 의 가상 설비가 이 표와 같아야 한다. 두 서비스는 코드를 공유하지 않으므로 각자 테스트(`SpecTablesTest`)가 이 표와 맞춰 본다 (#179). 설비를 더하거나 바꾸면 이 표, mes 마이그레이션, simulator 를 함께 바꾼다.

| 코드 | 공정 | 이름 |
|---|---|---|
| PRESS-01 | PRESS | 프레스기 1호 |
| PRESS-02 | PRESS | 프레스기 2호 |
| BODY-01 | BODY | 용접 로봇 1호 |
| BODY-02 | BODY | 용접 로봇 2호 |
| PAINT-01 | PAINT | 도장 로봇 1호 |
| PAINT-02 | PAINT | 도장 로봇 2호 |
| ASSEMBLY-01 | ASSEMBLY | 의장 라인 1호 |
| ASSEMBLY-02 | ASSEMBLY | 의장 라인 2호 |
| INSPECTION-01 | INSPECTION | 검사 라인 1호 |
| INSPECTION-02 | INSPECTION | 검사 라인 2호 |

## 규칙 / 예외
| 상황 | 처리 |
|---|---|
| RUNNING 으로 돌아옴 | 상태·이력만. 되돌린 공정을 다시 이 설비로 가져오지 않는다 (배정 스케줄러가 비어 있는 설비에 알아서 배정) |
| 고장인데 맡은 공정이 없음 | 상태·이력만 |
| 고장 전에 보낸 늦은 설비 보고 (step-started / step-completed) | 공정이 PENDING 이고 설비 코드도 지워졌으므로 지금 규칙대로 무시 (INFO) |
| 고장과 작업자의 공정 종료·시작이 동시에 같은 설비·작업지시를 바꿈 | 설비·작업지시 version 으로 한쪽만 반영. 리스너 쪽이 지면 재시도로 최신 상태를 보고 다시 판단 |
| 모르는 status 값, 깨진 JSON | 처리 오류 → 재시도 → DLQ |
| 같은 이벤트 두 번 | ProcessedEventGuard 로 한 번만 |
| 설비가 가리키는 공정이 이미 끝났거나 시작 전 (점유 기록 꼬임) | 공정은 그대로 두고(실적이 무효가 되지 않게) 설비만 비운다 (INFO) |
| 설비가 맡은 공정(occupied_step_id)이 없음 (데이터 꼬임) | 처리 오류 `STEP_NOT_FOUND` → 재시도 → DLQ. 아무것도 바뀌지 않는다 |
| payload 의 process 가 설비의 공정과 다름 | 설비 코드로만 찾고 process 는 쓰지 않는다 (코드가 설비를 정한다) |

### 생산 실패(FAILED) 제거
| 서비스 | 없애는 것 |
|---|---|
| production | `ProductionStatus.FAILED`, `WorkOrderStatus.FAILED`. "끝난 생산 건 / 닫힌 작업지시" 는 CANCELLED 만 (취소, #75) |
| order | `OrderStatus.FAILED`, 생산 상태 매핑의 FAILED, 요약 규칙 3번("남은 것이 모두 FAILED → FAILED") |
| 이벤트 | `production.failed` (events.md, 사람 적용) |
이미 적용된 마이그레이션의 컬럼 주석(상태 값 목록)은 고치지 않는다 (체크섬). DB 값 제약이 없어 스키마 변경은 없다.

### 테이블
| 테이블 | 컬럼 |
|---|---|
| `equipment_status_history` (mes V1. 처음엔 production V6, MES 분리로 옮김 docs/specs/mes-split.md) | id, equipment_code, from_status, to_status, changed_at, created_at. INDEX(equipment_code, changed_at). 추가만 한다 |

## 관련 이벤트 (events.md 참조)
| 이벤트 | 발행 → 수신 (큐) | payload |
|---|---|---|
| equipment.status-changed | simulator → mes (`mes.equipment-status-changed`) | equipmentCode, process, status (RUNNING, STOPPED, FAULT) |
| ~~production.failed~~ | 없앤다 | |

## 완료 조건 (테스트로 확인할 것)
- [ ] status-changed → 설비 상태 변경 + 이력 1줄 (from, to, 이벤트 시각). 같은 상태면 이력 없음
- [ ] FAULT → 그 설비에 DISPATCHED 인 공정 PENDING(설비·시작 시각 지움), 설비 비움. 작업지시·생산 건 상태 그대로, 실적·이벤트 없음
- [ ] STOPPED → 작업자가 진행 중(IN_PROGRESS)인 공정도 같은 처리
- [ ] 되돌린 로봇 공정이 다음 배정 때 같은 공정의 다른 RUNNING 설비로 배정된다
- [ ] 고장 전에 보낸 늦은 시작·완료 보고는 무시된다
- [ ] RUNNING 으로 돌아오면 상태·이력만 바뀐다
- [ ] 같은 이벤트 두 번 → 한 번만 반영 (두 번째가 같은 상태 규칙이 아니라 ProcessedEventGuard 로 걸러지는지). 없는 설비 → DLQ. 맡은 공정이 없으면 STEP_NOT_FOUND. 실제 브로커 경유 수신
- [ ] FAILED 가 production·order 코드에서 없어지고, 요약 규칙·매핑 테스트가 새 규칙과 맞다
- [ ] 이력 조회: 그 설비의 이력만 최신 먼저 `{fromStatus, toStatus, changedAt}`, nextCursor 를 lastId 로 넘기면 겹치지 않고 이어진다, 마지막 페이지 last = true·nextCursor null, 이력 없으면 빈 목록
- [ ] 이력 조회: 없는·삭제된 설비 404 `EQUIPMENT_NOT_FOUND`, 코드 형식·lastId 0 이하·size 범위 400. api-docs 에 summary 와 200·400·404
