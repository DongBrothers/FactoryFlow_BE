# simulator 재시작 때 설비 상태 맞추기 + 배정 회차 구분 + 완료 보고 시간 초과

## 목적
simulator 를 재시작하거나 보고가 늦게 올 때 production 과 설비가 어긋나는 두 문제를 고친다 (#104).

1. **설비 상태 어긋남**: simulator 는 재시작하면 설비를 모두 RUNNING 으로 시작하고 아무것도 보내지 않는다. production 이 FAULT·STOPPED 로 알던 설비는 계속 고장으로 남아 배정되지 않는다 (지금은 내부 API run 으로 손으로 맞춤)
2. **배정 회차 구분 없음**: 공정이 시작 보고 시간 초과로 되돌아갔다가 **같은 설비**에 다시 배정되면, 지난 배정의 늦은 시작·완료 보고(재전송, 밀린 메시지)를 이번 배정 것으로 받는다. 지금은 설비 코드와 공정 상태로만 거른다

- **설비가 기준이다.** 진짜 설비도 켜지면 자기 상태를 알린다. simulator 가 시작할 때 설비마다 지금 상태를 보낸다 (이벤트 계약 변경 없음)
- **배정 회차 번호**: 공정을 맡길 때마다 늘어나는 번호(dispatchSeq)를 배정에 싣고, 설비가 보고에 그대로 돌려준다. production 은 회차가 다른 보고를 무시한다

## 흐름

### 1. simulator 시작 → 설비 상태 알림
- 앱이 준비되면(ApplicationReadyEvent) 설비 10대 모두 `equipment.status-changed { equipmentCode, process, status: RUNNING }` 를 보낸다 (traceId 는 새로)
- production 은 지금 규칙대로 받는다: 다른 상태였으면 RUNNING 으로 바꾸고 이력 한 줄, 이미 RUNNING 이면 이력 없음 (docs/specs/equipment-status.md)
- 하던 작업은 simulator 가 잊었으므로, DISPATCHED·IN_PROGRESS 로 남은 공정은 지금처럼 production 의 시작 보고 시간 초과가 되돌린다 (IN_PROGRESS 는 아래 "남은 일")
- 브로커가 아직 안 떠 있으면 지금의 재전송(resend)이 확인될 때까지 보낸다

### 2. 배정 회차 번호 (production ↔ simulator)
**production**
- `work_order_step.dispatch_seq` (INT, 0 부터). 공정을 맡길 때마다 +1: 로봇 배정(DISPATCHED)과 작업자 시작(IN_PROGRESS) 둘 다
- `production.step-dispatched` 에 `dispatchSeq` 를 싣는다
- 설비 보고(`equipment.step-started`, `equipment.step-completed`)의 `dispatchSeq` 가 공정의 지금 값과 다르면 무시한다 (INFO 로그). 설비 코드·공정 상태 확인은 그대로
- 보고에 `dispatchSeq` 가 없으면(이 변경 전 메시지) 지금 규칙(설비 코드·상태)으로만 판단한다

**simulator**
- 받은 배정의 `dispatchSeq` 를 작업(Job)에 기억하고, 그 작업의 시작·완료 보고에 그대로 싣는다

### 3. 완료 보고 시간 초과 (production, #111)
설비가 시작 보고를 보낸 뒤 재시작하거나 완료 보고가 사라지면, 로봇 공정이 IN_PROGRESS 로 남고 설비도 점유된 채 멈춘다. 시작 보고 시간 초과(DISPATCHED)처럼 되돌린다.
- 대상: **설비가 진행하는 공정**만. IN_PROGRESS 이고 배정(dispatched_at)으로 시작했으며, 시작(started_at)한 지 `completion-report-timeout`(기본 5분)이 지난 공정
  - 작업자가 시작한 공정(사람 공정, 로봇 공정 수동 개입)은 시간이 걸려도 되돌리지 않는다. 그래서 작업자 시작은 dispatched_at 을 비운다
- 처리: 시작 보고 시간 초과와 같다. 배정 스케줄러가 바퀴마다 정해진 개수씩 PENDING 으로 되돌리고 설비를 비운다 (실적 없음). 다음 배정에서 회차가 올라 늦게 온 지난 완료 보고는 무시된다
- 생산 시간보다 넉넉히 둔다 (simulator 작업 시간은 수 초)

## 규칙 / 예외
| 상황 | 처리 |
|---|---|
| 지난 회차 시작 보고가 같은 설비의 새 배정 중에 옴 | 회차가 달라 무시. 새 배정은 그대로 DISPATCHED |
| 지난 회차 완료 보고가 옴 | 무시. 실적·공정 완료 없음 |
| 작업자가 수동으로 시작한 공정에 설비 보고가 옴 | 회차가 올라가 지난 배정 보고는 무시 |
| 불량으로 PENDING → 다시 배정 | 회차 +1, 지난 회차 보고는 무시 |
| simulator 시작 알림을 두 번 받음 (재전송) | eventId 가 같으면 Guard, 이미 RUNNING 이면 이력 없음 |
| production 이 FAULT 로 알던 설비 | simulator 시작 알림으로 RUNNING, 다음 바퀴부터 배정 |
| 완료 보고 없이 시간이 지난 설비 공정 | PENDING 으로 되돌려 다시 배정, 늦은 지난 회차 완료 보고는 무시 |
| 작업자가 진행 중인 공정 | 시간이 지나도 그대로 |

### 테이블 변경
| 서비스 | 변경 |
|---|---|
| production (V8) | `work_order_step.dispatch_seq` (INT NOT NULL DEFAULT 0) |
| simulator | 없음 (DB 없음) |

## 관련 이벤트 (events.md 참조)
| 이벤트 | 발행 → 수신 | payload |
|---|---|---|
| production.step-dispatched | production → simulator | workOrderId, process, equipmentCode, **dispatchSeq** |
| equipment.step-started | simulator → production | equipmentCode, workOrderId, process, **dispatchSeq** |
| equipment.step-completed | simulator → production | equipmentCode, workOrderId, process, result, defectReason, **dispatchSeq** |
| equipment.status-changed | simulator → production | equipmentCode, process, status (시작할 때도 보냄) |
세 이벤트에 `dispatchSeq` 를 더한다 (필드 추가, version 1 그대로, events.md 사람 적용).

## 완료 조건 (테스트로 확인할 것)
simulator
- [ ] 시작하면 설비 10대의 status-changed(RUNNING) 가 나간다
- [ ] 배정의 dispatchSeq 가 시작·완료 보고에 그대로 실린다
production
- [ ] 배정·작업자 시작마다 dispatch_seq +1, step-dispatched 에 dispatchSeq
- [ ] 회차가 다른 시작·완료 보고는 무시 (같은 설비에 다시 배정된 경우), 회차가 같으면 지금처럼 반영
- [ ] dispatchSeq 없는 보고는 지금 규칙으로 반영
- [ ] FAULT 설비에 RUNNING 이 오면 RUNNING + 이력 (지금 규칙 확인)
- [ ] V8 마이그레이션 (기본값 0)
- [ ] 완료 보고 없이 completion-report-timeout 이 지난 설비 공정은 PENDING + 설비 비움, 다음 바퀴에 다시 배정 (#111)
- [ ] 작업자가 시작한 공정, 시간이 덜 지난 공정은 그대로, 되돌린 뒤 온 지난 회차 완료 보고는 무시
