# 설비 상태 수신과 고장·정지 시 재배정 (equipment.status-changed)

## 목적
주문 이행 SAGA(docs/specs/saga-order-fulfillment.md) "3. 설비 상태" 다 (#93).
production 이 `equipment.status-changed` 를 받아 설비 상태를 바꾸고 이력을 남긴다 (OEE 데이터).
고장(FAULT)이나 정지(STOPPED)가 되면 **그 설비에** 배정되었거나(DISPATCHED) 진행 중(IN_PROGRESS)인 공정을 "시작 전"(PENDING)으로 되돌리고 설비를 비운다.
배정 스케줄러(로봇 공정)나 작업자(사람 공정)가 같은 공정의 다른 설비로 이어서 진행하고, 다른 설비가 없으면 고쳐질 때까지 기다린다.

설비 고장은 공장 사정이다. 실제 공장도 차를 버리지 않고 다른 설비로 돌리거나 고친 뒤 이어서 만든다. 그래서 **생산 실패는 없다** (2026-10-16 결정):
생산 건·작업지시·주문 상태와 부품 예약은 그대로이고, 고객에게는 계속 "생산 중" 으로 보인다 (늦어질 뿐).
이전 설계의 `production.failed`, 생산 건·작업지시·주문의 FAILED 상태는 없앤다.

보내는 쪽(simulator)은 #74 에서 만든다. 받는 쪽을 먼저 만든다 (설비 보고 수신과 같은 방식).

## 흐름
1. simulator 가 설비 상태가 바뀔 때마다 `equipment.status-changed { equipmentCode, process, status }` 를 보낸다 (status: RUNNING, STOPPED, FAULT)
2. production 이 큐 `production.equipment-status-changed` 로 받는다. `ProcessedEventGuard` 로 같은 이벤트를 한 번만 처리하고, 아래는 처리 기록과 같은 트랜잭션이다
3. 설비를 코드로 찾는다. 없으면 처리 오류 `EQUIPMENT_NOT_FOUND` → 재시도 → DLQ
4. 상태가 지금과 같으면 아무것도 하지 않는다 (INFO 로그)
5. 상태를 바꾸고 이력 1줄: `equipment_status_history { equipment_code, from_status, to_status, changed_at }`. changed_at 은 이벤트가 생긴 시각(envelope.occurredAt)
6. 새 상태가 FAULT 또는 STOPPED 이고 설비가 맡은 공정이 있으면(occupied_step_id) 그 공정을 되돌린다
   - 공정: DISPATCHED·IN_PROGRESS → PENDING (설비 코드·시작 시각 지움). 실적은 남기지 않는다 (끝난 게 아니라 중단됐다)
   - 설비: 맡은 공정 비움
   - 작업지시: 공정 변경 기록(step_changed_at, version). 상태는 그대로
   - 생산 건·주문: 그대로. 이벤트를 보내지 않는다
7. 되돌린 공정은 다음 배정 주기에 같은 공정의 다른 RUNNING 설비로 다시 배정된다 (로봇 공정). 사람 공정은 작업자가 다른 설비로 다시 시작한다

## 규칙 / 예외
| 상황 | 처리 |
|---|---|
| RUNNING 으로 돌아옴 | 상태·이력만. 되돌린 공정을 다시 이 설비로 가져오지 않는다 (배정 스케줄러가 비어 있는 설비에 알아서 배정) |
| 고장인데 맡은 공정이 없음 | 상태·이력만 |
| 고장 전에 보낸 늦은 설비 보고 (step-started / step-completed) | 공정이 PENDING 이고 설비 코드도 지워졌으므로 지금 규칙대로 무시 (INFO) |
| 고장과 작업자의 공정 종료·시작이 동시에 같은 설비·작업지시를 바꿈 | 설비·작업지시 version 으로 한쪽만 반영. 리스너 쪽이 지면 재시도로 최신 상태를 보고 다시 판단 |
| 모르는 status 값, 깨진 JSON | 처리 오류 → 재시도 → DLQ |
| 같은 이벤트 두 번 | ProcessedEventGuard 로 한 번만 |
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
| `equipment_status_history` (production, V6) | id, equipment_code, from_status, to_status, changed_at, created_at. INDEX(equipment_code, changed_at). 추가만 한다 |

## 관련 이벤트 (events.md 참조)
| 이벤트 | 발행 → 수신 (큐) | payload |
|---|---|---|
| equipment.status-changed | simulator → production (`production.equipment-status-changed`) | equipmentCode, process, status (RUNNING, STOPPED, FAULT) |
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
