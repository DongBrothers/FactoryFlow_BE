# 공정 센서값 (설비 텔레메트리)

## 목적
로봇 공정(PRESS·BODY·PAINT)을 하는 동안 설비에서 나오는 값(프레스 압력, 용접 전류, 도장 부스 온도 등)을 남긴다. 불량이 났을 때 "그 공정 중 값이 어땠는지" 를 시간 순으로 보고 원인을 좁히려고 한다.
- 공정 실적(양품/불량, 시각, 설비)은 지금처럼 mes 의 MySQL(mes_db)에 둔다. 센서값은 양이 많고 설비마다 항목이 달라 **MongoDB 시계열 컬렉션**에 따로 둔다
- 센서값은 업무 흐름(주문·생산·출고)과 묶지 않는다. 저장이 늦거나 실패해도 공정은 그대로 진행된다
- 사람 공정(ASSEMBLY·INSPECTION)의 값, 실시간 화면(스트리밍), 기준을 벗어난 값 알림은 하지 않는다

MongoDB 는 전용 EC2(ff-mongo)에 docker 로 띄운다 (RabbitMQ 와 장애를 나눈다). 서비스가 NAT 없는 private subnet 에 있어 외부 Atlas 에 나갈 수 없고, DocumentDB 는 이 규모에 비싸며(월 8~10만 원+) 시계열 컬렉션을 지원하지 않는다.

```
simulator: 공정 중 0.2초마다 값 기록 → 완료 보고(equipment.step-completed) 직후
           equipment.step-telemetry 한 번에 보냄
   ↓ RabbitMQ
mes: 큐 mes.equipment-step-telemetry → MongoDB step_telemetry 에 샘플마다 문서 1개
   ↓
조회: 작업지시의 공정별, 설비의 시간 범위별
```

## 흐름

### 1. simulator: 값 만들기
- 배정받은 로봇 공정을 하는 동안 `sample-interval`(기본 0.2초)마다 그 공정의 항목 값을 만들어 작업(Job)에 모은다. 공정 시간이 3~5초라 공정당 15~25개
- 완료 보고를 보낸 직후 `equipment.step-telemetry` 를 한 번 보낸다. 고장·정지로 작업을 버리면 보내지 않는다 (완료 보고도 없음)
- 값은 정상 범위 안에서 조금씩 흔들리게 만든다. 불량으로 보고하는 공정은 그 공정의 대표 항목이 중간부터 범위를 벗어나게 만든다 (불량 사유와 맞게)

| 공정 | 항목 | 단위 | 정상 범위 | 불량일 때 벗어나는 항목 (사유) |
|---|---|---|---|---|
| PRESS | `pressureTon` 프레스 압력 | ton | 780 ~ 820 | pressureTon (찍힘) |
| PRESS | `strokeMm` 스트로크 | mm | 295 ~ 305 | |
| BODY | `weldCurrentKa` 용접 전류 | kA | 9.5 ~ 10.5 | weldCurrentKa (용접 불량) |
| BODY | `weldVoltageV` 용접 전압 | V | 22 ~ 26 | |
| PAINT | `boothTempC` 부스 온도 | °C | 21 ~ 25 | |
| PAINT | `humidityPct` 습도 | % | 55 ~ 65 | |
| PAINT | `filmThicknessUm` 도막 두께 | µm | 100 ~ 120 | filmThicknessUm (도장 얼룩) |

- 범위는 화면에 기준선으로 보여 주려고 mes 도 같은 값을 갖는다 (서비스끼리 코드를 공유하지 않으므로 양쪽에 같은 표를 둔다)

### 2. mes: 저장
- 큐 `mes.equipment-step-telemetry` (DLQ 포함), `ProcessedEventGuard` 로 같은 메시지는 한 번만 (메시지가 공정당 1개라 처리 기록도 공정당 1행)
- MongoDB 데이터베이스 `mes`, 시계열 컬렉션 `step_telemetry`
  - timeField `measuredAt`, metaField `meta`, 단위 seconds, 보관 90일(`expireAfterSeconds`) 뒤 자동 삭제
  - 문서 = 샘플 1개: `{ measuredAt, meta: { equipmentCode, workOrderId, process, dispatchSeq }, values: { pressureTon: 812.4, strokeMm: 300.1 } }`
  - 한 메시지의 샘플을 한 번에 넣는다 (insertMany)
- 컬렉션이 없으면 mes 가 뜰 때 시계열 컬렉션으로 만든다
- 공정 상태(MySQL)와 같은 트랜잭션이 아니다. 작업지시가 없거나 끝났어도 받은 값은 저장한다 (늦게 온 값도 기록으로 의미가 있음)
- MongoDB 에 저장하지 못하면 재시도 후 DLQ. 공정 진행 리스너(시작·완료 보고)와 큐가 달라 영향을 주지 않는다

### 3. 조회 (mes, 읽기 역할)
`GET /mes/work-orders/{workOrderId}/steps/{process}/telemetry` (gateway `/api/mes/...`)
```json
{ "workOrderId": 12, "process": "PRESS",
  "limits": [ { "name": "pressureTon", "unit": "ton", "lower": 780, "upper": 820 }, … ],
  "runs": [ { "dispatchSeq": 1, "equipmentCode": "PRESS-01",
              "samples": [ { "measuredAt": "2026-10-20T10:00:00.200", "values": { "pressureTon": 801.2, "strokeMm": 299.8 } } ] } ],
  "truncated": false }
```
- 재작업으로 같은 공정을 다시 했으면 배정 회차(dispatchSeq)별로 나눠 준다 (`runs`, 회차 순)
- 샘플은 시간 순. 값이 없으면 `runs` 가 빈 목록
- process 는 로봇 공정만 (PRESS·BODY·PAINT). 사람 공정이면 400

`GET /mes/equipments/{equipmentCode}/telemetry?from=&to=`
- 설비 하나의 값을 시간 범위로 (최신 범위 확인용). from·to 는 ISO 시각, to − from 은 최대 1시간
- 응답: `{ equipmentCode, limits, samples: [ { measuredAt, workOrderId, dispatchSeq, values } ], truncated }` 시간 순

## 메모리 상한 (OOM 방지)
센서값은 양이 많아 메모리에 한꺼번에 올라가는 곳마다 상한을 둔다.
| 위치 | 상한 | 넘으면 |
|---|---|---|
| simulator 값 생성 | 공정당 최대 300개, 수집 간격 최소 50ms | 300개까지만 만든다 |
| simulator 보내기 | 센서값은 확인 대기 목록(PendingMessages)에 넣지 않고 한 번만 보낸다 | 보내기 실패면 버린다 (WARN). 브로커가 오래 멈춰도 메모리에 쌓이지 않는다 |
| mes 수신 | 메시지당 300개 (`factoryflow.telemetry.max-samples-per-message`) | 저장하지 않고 재시도 후 DLQ (비정상 메시지) |
| mes 조회 | 한 번에 5,000개 (`factoryflow.telemetry.max-query-samples`) | 앞에서부터 5,000개만 주고 `truncated: true`. 범위를 좁혀 다시 본다 |

## 규칙 / 예외
| 상황 | 처리 |
|---|---|
| 같은 telemetry 메시지 두 번 | Guard 로 한 번만 저장 |
| 고장·정지로 버린 작업 | simulator 가 보내지 않는다 |
| MongoDB 장애 | 재시도 후 DLQ, 공정 진행은 그대로 |
| mes 수신: samples 가 없음 | 저장하지 않고 경고 로그 (재시도·DLQ 없음) |
| 조회: process 가 사람 공정이거나 모르는 값 | 400 |
| 조회: workOrderId 가 양수가 아님, 설비 코드 형식 오류 | 400 |
| 조회: from 이 to 보다 늦음, 범위가 1시간 초과, from·to 형식 오류 | 400 |
| 조회: 작업지시 없음 | 404 `WORK_ORDER_NOT_FOUND` |
| 조회: 설비 없음 | 404 `EQUIPMENT_NOT_FOUND` |

- 새 오류 코드 `TELEMETRY_TOO_LARGE`: 메시지당 상한을 넘은 센서값 메시지를 저장하지 않을 때 리스너가 던진다 (재시도 후 DLQ). API 응답으로는 나가지 않는다

## 인프라
| 항목 | 내용 |
|---|---|
| MongoDB | 전용 EC2 `ff-mongo` (t3.small, Amazon Linux 2023, ff-rabbitmq 와 같은 서브넷) 에 `mongo:7` 컨테이너. 데이터는 docker 볼륨 `mongo`, 재시작 자동(`--restart always`) |
| 계정 | root 는 관리용, mes 는 `mes` 데이터베이스 readWrite 전용 계정 |
| 네트워크 | 보안 그룹 `ff-sg-mongo`(27017 인바운드는 서비스 보안 그룹 sg-0535230f2f50fab11 에서만)와 `ff-sg-ssh`(22, 관리자 IP)를 인스턴스에 붙인다 |
| 설정 | APP_DEV_YML_MES 에 `spring.mongodb.uri` (ff-mongo 사설 IP). 로컬은 docker-compose 의 mongo |
| 테스트 | Testcontainers MongoDB (mes 테스트의 MongoContainerConfig) |

## 설정 위치
센서값 설정을 넣으면서, 환경마다 다른 값은 공통 `application.yml` 에서 환경별 파일로 옮긴다 (2026-10-22 결정).
- mes: `factoryflow.line.*`(배정), `spring.mongodb.uri` → `application-{local,dev,test}.yml`
- simulator: `factoryflow.simulator.*`(작업 시간, 불량 확률, 센서값 간격 등) → `application-{local,dev,test}.yml`
- 테스트는 test 프로필로 돈다 (`src/test/resources/application-test.yml`, mes 는 배정 스케줄러를 끈다). 설정이 없으면 기본값(배정 켬, 5초 주기)이라 dev 시크릿에 값이 빠져도 배정은 돈다
- `LinePropertiesTest` 는 "application.yml 에 배정 설정이 있다" 대신 "공통 파일에는 없고 test 파일은 배정을 끈다" 를 확인한다

## 관련 이벤트 (events.md 참조, 사람만 수정)
| 이벤트 | 발행 → 수신 (큐) | payload |
|---|---|---|
| equipment.step-telemetry | simulator → mes (`mes.equipment-step-telemetry`) | equipmentCode, workOrderId, process, dispatchSeq, samples[{at, values{이름: 값}}] |

## 완료 조건 (테스트로 확인할 것)
simulator
- [ ] 로봇 공정 완료 보고 뒤 step-telemetry 1건, 샘플 수 ≈ 공정 시간 / sample-interval, 항목은 그 공정의 것
- [ ] 양품이면 모든 값이 범위 안, 불량이면 대표 항목이 범위 밖인 샘플이 있음
- [ ] 고장·정지로 버린 작업은 telemetry 없음
- [ ] 센서값은 확인 대기 목록에 남지 않음 (한 번만 보냄), 공정당 300개 이하
mes
- [ ] step-telemetry 수신 → step_telemetry 에 샘플 수만큼 문서, meta·values 그대로
- [ ] 같은 메시지 두 번 → 한 번만 저장
- [ ] 컬렉션이 시계열(timeField measuredAt, metaField meta, expireAfterSeconds 90일)로 만들어짐
- [ ] 작업지시 공정별 조회: 회차별 runs, 시간 순, limits, 사람 공정 400, 없는 작업지시 404
- [ ] 설비 시간 범위 조회: 범위 안 샘플만 시간 순, 1시간 초과·순서 오류 400, 없는 설비 404
- [ ] Swagger: 두 API summary·응답 코드
- [ ] 실제 브로커 경유 수신
- [ ] 메시지당 상한을 넘으면 저장하지 않음, 조회 상한을 넘으면 앞에서부터 상한만큼 + truncated true
