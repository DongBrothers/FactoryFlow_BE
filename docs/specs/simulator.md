# 가상 설비 (simulator) + 배정 스케줄러 켜기

## 목적
주문 이행 SAGA(docs/specs/saga-order-fulfillment.md) "2. 공정 진행" 의 로봇 공정 설비 쪽과 "3. 설비 상태" 의 보내는 쪽이다 (#74).
simulator 는 진짜 설비(프레스기, 용접·도장 로봇, 의장·검사 라인)를 흉내 낸다. 업무 판단은 하지 않는다.
- production 의 배정(`production.step-dispatched`)을 받아 작업 시간을 흉내 낸 뒤 시작·완료(가끔 불량)를 보고한다
- 설비 상태(가동·정지·고장)를 보낸다. 고장은 설정한 확률로 가끔 나고, 내부 API 로 직접 낼 수도 있다
- production 은 배정 스케줄러를 켠다. 배정 뒤 시작 보고가 오지 않으면(simulator 재시작, 통신 끊김) 그 공정을 되돌려 다시 배정한다

이렇게 되면 로봇 공정(프레스 → 차체 → 도장)이 사람 손 없이 자동으로 진행된다. 사람 공정(의장·검사)은 지금처럼 작업자 API 다.

### simulator 에 DB 를 두지 않는 이유 (2026-10-16 결정)
- 진짜 설비는 MES 의 DB 를 갖지 않는다. 배정을 받고 일하고 결과를 보고할 뿐이다
- Outbox 는 "업무 저장과 이벤트 발행을 함께" 하려는 것인데 simulator 에는 함께 저장할 업무 데이터가 없다. 브로커가 받았다고 확인할 때까지 메모리에서 다시 보내면 된다
- 중복은 받는 쪽(production)이 처리 기록과 상태 확인으로 이미 거른다. simulator 는 메모리로 거른다
- 그래서 common-event(JPA 필요)를 쓰지 않고 메시지 모양(EventEnvelope 의 JSON)만 맞춘다. FactoryFlowRules 의 `NO_DIRECT_PUBLISH`, `LISTENER_USES_GUARD` 에서 simulator 를 뺀다 (사람 적용)
- 재시작하면 하던 작업을 잊는다. 이건 production 의 "시작 보고 시간 초과" 가 되돌린다 (진짜 설비의 통신 끊김도 같은 방법으로 해결)

## 흐름

### 1. 설비 (simulator, 메모리)
- 설비 10대: production 의 equipment 와 같은 코드·공정 (PRESS-01·02, BODY-01·02, PAINT-01·02, ASSEMBLY-01·02, INSPECTION-01·02). 시작할 때 모두 RUNNING
- 설비마다 상태(RUNNING, STOPPED, FAULT)와 지금 하는 작업(없거나 하나)을 갖는다
- 시작할 때 상태 이벤트는 보내지 않는다 (production 도 RUNNING 으로 시작)

### 2. 배정 수신 → 작업 → 보고
1. 큐 `simulator.production-step-dispatched` 로 `production.step-dispatched { workOrderId, process, equipmentCode }` 를 받는다
2. 받은 eventId 를 메모리에 기억해 같은 메시지는 한 번만 처리한다 (최근 것만, 개수 제한)
3. 설비가 없거나, RUNNING 이 아니거나, 이미 다른 작업 중이면 무시한다 (INFO 로그). production 이 시작 보고 시간 초과로 되돌려 다시 배정한다
4. 작업을 맡는다. 시작 지연(`start-delay`, 기본 1초) 뒤 `equipment.step-started { equipmentCode, workOrderId, process }`
5. 작업 시간(공정별 `work-time`, 기본 PRESS 3초, BODY 4초, PAINT 5초) 뒤 `equipment.step-completed { equipmentCode, workOrderId, process, result, defectReason }`
   - 불량 확률(`defect-rate`, 기본 0.05)로 DEFECT, 사유는 공정별 목록에서 하나 (예: 프레스 "찍힘", 차체 "용접 불량", 도장 "도장 얼룩")
   - 보고하면 설비가 빈다
6. 보고들은 받은 배정의 traceId 를 이어 쓴다
7. 사람 공정 설비(ASSEMBLY, INSPECTION)는 배정을 받지 않는다 (production 이 배정하지 않음. 잘못 와도 무시). 상태만 보낸다

### 3. 설비 상태 (고장·수리)
- 고장 확률: `fault-check-interval`(기본 30초)마다 RUNNING 설비마다 `fault-rate`(기본 0, 끔) 확률로 FAULT. `repair-time`(기본 60초) 뒤 RUNNING
- 내부 API (서비스끼리·시연용, gateway 에 노출하지 않음, Swagger 없음)
  - `GET /internal/simulator/equipments`: 설비 상태와 지금 하는 작업
  - `POST /internal/simulator/equipments/{code}/fault`: 고장 (FAULT)
  - `POST /internal/simulator/equipments/{code}/stop`: 정지 (STOPPED)
  - `POST /internal/simulator/equipments/{code}/run`: 가동 (RUNNING)
  - 설비 코드 형식(`{공정}-{두 자리 번호}`, 예: PRESS-01)이 아니면 400. 없는 설비 404 `EQUIPMENT_NOT_FOUND`. 같은 상태로 바꾸면 그대로 (이벤트 없음)
- 상태가 바뀌면 `equipment.status-changed { equipmentCode, process, status }`
- FAULT·STOPPED 가 되면 하던 작업을 버린다 (시작·완료 보고를 보내지 않음). production 이 그 공정을 되돌려 다른 설비로 다시 배정한다 (docs/specs/equipment-status.md)

### 4. 보내기 (Outbox 없이 바로, 확인될 때까지 다시)
- exchange `factoryflow.events`, 라우팅 키 = 이벤트 이름, 메시지 = EventEnvelope 와 같은 JSON `{ eventId, eventName, traceId, version 1, occurredAt, payload }`, messageId = eventId
- 브로커 확인(publisher confirm)을 받으면 끝. 거부(nack)·확인 없음·연결 실패면 메모리에 두고 `resend-interval`(기본 1초)마다 다시 보낸다 (같은 eventId)
- 재시작하면 아직 확인 못 받은 메시지는 사라진다 (아래 production 시간 초과가 되돌림)

### 5. production 쪽 변경
1. 배정 스케줄러를 켠다 (`factoryflow.line.dispatch-enabled: true`). 테스트는 지금처럼 끈다
2. **시작 보고 시간 초과**: 배정 스케줄러가 바퀴마다 DISPATCHED 로 `start-report-timeout`(기본 1분)보다 오래된 공정을 PENDING 으로 되돌리고 설비를 비운다 (고장 때 되돌리기와 같은 처리, 실적 없음). 다음 바퀴에 다시 배정된다
   - 공정에 배정 시각 `dispatched_at` 을 남긴다 (V7)
   - 한 번에 다 읽지 않고 정해진 개수씩 처리한다
3. **완료 보고가 시작 보고보다 먼저 와도 반영**: 시작·완료는 큐가 달라 순서가 바뀔 수 있다 (재전송, 밀린 메시지). 지금은 DISPATCHED 공정에 온 완료를 무시하는데, 그러면 공정이 끝난 차가 DISPATCHED 에 남았다가 시간 초과로 되돌아가 같은 작업을 또 한다. 그래서 DISPATCHED 이고 같은 설비면 시작과 완료를 함께 반영한다 (시작 시각 = 완료 보고 시각). 뒤늦게 온 시작 보고는 공정이 DONE·PENDING 이라 지금 규칙대로 무시된다
   - 기존 테스트 `mismatchedReportsAreIgnored` 의 "완료가 시작보다 먼저 옴 → 무시" 부분이 이 규칙과 반대라 바꾼다 (확인 필요)

## 규칙 / 예외
| 상황 | 처리 |
|---|---|
| 같은 배정 메시지를 두 번 받음 | eventId 로 한 번만. 기억에서 밀려난 아주 오래된 재전달은 설비가 이미 다른 작업 중이거나 production 이 상태로 거른다 |
| 배정받은 설비가 고장·정지·작업 중 | 무시. production 의 시작 보고 시간 초과가 되돌린다 |
| 작업 중 고장·정지 | 작업을 버리고 상태만 보낸다 |
| RabbitMQ 가 잠시 죽음 | 보낼 메시지를 메모리에 두고 다시 보낸다. 받는 쪽 큐는 살아나면 처리 |
| simulator 재시작 | 설비가 모두 RUNNING, 작업 없음. DISPATCHED 로 남은 공정은 production 이 시간 초과로 되돌린다. production 이 FAULT 로 알고 있던 설비는 simulator 가 RUNNING 이므로 상태가 어긋날 수 있다 (내부 API run 으로 맞춘다, 이후 보완) |
| 깨진 배정 메시지 | 재시도 후 DLQ (`simulator.production-step-dispatched.dlq`) |

### 설정 (simulator, `factoryflow.simulator.*`)
| 키 | 기본값 | 뜻 |
|---|---|---|
| `start-delay` | 1s | 배정을 받고 시작 보고까지 |
| `work-time.PRESS` / `.BODY` / `.PAINT` | 3s / 4s / 5s | 시작 보고부터 완료 보고까지 |
| `defect-rate` | 0.05 | 완료가 불량일 확률 |
| `fault-check-interval` | 30s | 고장을 뽑는 주기 |
| `fault-rate` | 0 | 주기마다 가동 중인 설비마다 고장 날 확률 (0 이면 자동 고장 없음) |
| `repair-time` | 60s | 자동 고장에서 RUNNING 으로 돌아오기까지 |
| `resend-interval` | 1s | 확인 못 받은 메시지를 다시 보내는 주기 |
| `seen-event-limit` | 10000 | 중복 확인용으로 기억하는 eventId 개수 |

### 테이블 변경
| 서비스 | 변경 |
|---|---|
| production (V7) | `work_order_step.dispatched_at` (DATETIME(6) NULL). INDEX(status, dispatched_at) |
| simulator | 없음 (DB 없음) |

## 관련 이벤트 (events.md 참조, 변경 없음)
| 이벤트 | 발행 → 수신 (큐) | payload |
|---|---|---|
| production.step-dispatched | production → simulator (`simulator.production-step-dispatched`) | workOrderId, process, equipmentCode |
| equipment.step-started | simulator → production (`production.equipment-step-started`) | equipmentCode, workOrderId, process |
| equipment.step-completed | simulator → production (`production.equipment-step-completed`) | equipmentCode, workOrderId, process, result, defectReason |
| equipment.status-changed | simulator → production (`production.equipment-status-changed`) | equipmentCode, process, status |

### 사람 적용 (common-test)
- `FactoryFlowRules.NO_DIRECT_PUBLISH`: `com.factoryflow.simulator..` 를 뺀다 (DB 가 없어 Outbox 를 쓸 수 없음)
- `FactoryFlowRules.LISTENER_USES_GUARD`: `com.factoryflow.simulator..` 를 뺀다 (처리 기록 테이블이 없음. 메모리로 중복을 거름)
- `TestContainersConfig`: MySQL 컨테이너는 JDBC 가 있는 서비스에만 띄운다 (`@ConditionalOnClass(JdbcConnectionDetails)`). DB 없는 simulator 테스트는 MySQL 연결 정보를 받을 곳이 없어 컨텍스트가 뜨지 않는다. 다른 서비스는 그대로

## 완료 조건 (테스트로 확인할 것)
simulator (Testcontainers RabbitMQ, 시간은 설정으로 짧게)
- [ ] 배정을 받으면 시작 → 완료(GOOD) 보고가 순서대로 나가고, 받은 traceId 를 이어 쓴다. 메시지 모양이 EventEnvelope 와 같다
- [ ] 불량 확률 1 이면 DEFECT 와 사유
- [ ] 같은 배정 두 번 → 작업 한 번. 고장·정지·작업 중인 설비, 없는 설비에 온 배정은 무시
- [ ] 내부 API 로 고장 → status-changed(FAULT), 하던 작업은 보고하지 않음. run → RUNNING. 같은 상태는 이벤트 없음. 없는 설비 404
- [ ] 자동 고장: fault-rate 1 이면 고장 나고 repair-time 뒤 RUNNING
- [ ] 브로커 확인을 못 받은 메시지는 다시 보낸다 (확인 처리 단위 테스트)
production
- [ ] 시작 보고 없이 시간이 지난 DISPATCHED 공정은 PENDING 으로 돌아가고 설비가 빈다, 다음 바퀴에 다시 배정
- [ ] DISPATCHED 공정에 온 완료 보고는 시작과 완료를 함께 반영한다, 뒤늦은 시작 보고는 무시
- [ ] dispatch-enabled 기본 true (테스트는 끔)
