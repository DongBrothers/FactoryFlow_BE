# 완성차 보관 위치

## 목적
완성차가 창고(IN_STOCK)에 들어오면 **어느 자리에 있는지** 기록한다. 지금은 상태만 있어서 출고 담당이 VIN 만 보고 차를 찾아야 한다.
실제 완성차 출고장처럼 정해진 자리(구역 + 번호)에 차를 세우고, 출고하면 자리를 비운다.

- 자리는 **자동 배정**한다. 완성차 입고는 사람이 누르는 API 가 아니라 `production.completed` 수신이라, 받는 순간 빈 자리를 정한다
- 위치는 inventory 안에서만 쓴다. 이벤트·다른 서비스는 바뀌지 않는다
- 부품 창고는 지금처럼 하나로 본다 (부품까지 자리를 나누면 예약·LOT 이 자리마다 갈라진다)
- 자리 옮기기, 출고장 여러 곳, 자리 등록·삭제 API 는 하지 않는다

```
production.completed
  ↓ inventory
완성차 IN_STOCK + 빈 자리 중 코드가 가장 앞선 자리 (예: A-01)
  ↓ 빈 자리가 없으면
완성차 IN_STOCK, 자리 없음 (자리 대기)
  ↓ 출고 (POST /inventory/finished-vehicles/{vin}/ship)
완성차 SHIPPED, 자리 비움 → 자리 대기 중인 가장 오래된 차를 그 자리에 넣음
```

## 흐름

### 1. 자리 (storage_slot, 마이그레이션으로 넣음)
- 출고장 하나, 구역 A·B, 구역마다 20자리 → 40자리. 코드 `A-01` … `A-20`, `B-01` … `B-20`
- 자리마다 지금 서 있는 차(`finished_vehicle_id`, 비면 NULL) 하나

### 2. 완성차 입고 때 자리 배정 (inventory ← production.completed)
`FinishedVehicleService.stock` 안, 같은 트랜잭션
1. 지금처럼 부품 사용·LOT 기록·완성차 IN_STOCK
2. 빈 자리 중 코드 순 첫 자리를 찾아 그 차를 넣는다. 완성차에도 자리 코드를 적는다
3. 빈 자리가 없으면 자리 없이 IN_STOCK 으로 둔다 (WARN 로그 `자리 없음: vin=…`). 완성을 실패시키지 않는다

### 3. 출고 때 자리 비우기 (`POST /inventory/finished-vehicles/{vin}/ship`, 같은 트랜잭션)
1. 지금처럼 SHIPPED + `shipment.completed`
2. 그 차가 서 있던 자리를 비운다. 완성차의 자리 코드는 기록으로 남긴다 (어디서 나갔는지)
3. 자리 대기 중인 차(IN_STOCK 이고 자리 없음)가 있으면 가장 먼저 완성된 차(완성차 id 순)를 그 자리에 넣는다

### 4. 조회
- 완성차 목록·출고 응답에 `slotCode` 추가 (자리 대기면 null)
- 자리 현황 `GET /inventory/storage-slots` (업무 역할 모두 읽기, gateway `/api/inventory/storage-slots`)
```json
{ "items": [ { "slotCode": "A-01", "zone": "A", "vin": "KMH000017TU000012" },
             { "slotCode": "A-02", "zone": "A", "vin": null } ],
  "total": 40, "occupied": 1, "waitingVehicles": 0 }
```
  - 40자리를 코드 순으로 한 번에 준다 (자리 수가 고정이라 페이지 없음)
  - `waitingVehicles` = 자리 대기 중인 완성차 수

## 규칙 / 예외
| 상황 | 처리 |
|---|---|
| 두 차가 동시에 완성돼 같은 빈 자리를 고름 | 자리 version 으로 늦은 쪽이 충돌 → 리스너 재시도 (다시 고르면 다음 빈 자리) |
| 출고와 입고가 같은 자리를 동시에 다룸 | 자리 version 으로 늦은 쪽 충돌. 출고는 409 `FINISHED_VEHICLE_CONFLICT`, 리스너는 재시도 |
| 자리가 다 참 | 자리 없이 IN_STOCK, 출고로 자리가 나면 먼저 완성된 차부터 들어간다 |
| 같은 production.completed 를 두 번 받음 | 지금처럼 이미 완성차가 있으면 건너뜀 (자리도 다시 잡지 않음) |
| 자리 현황 조회 | 형식 오류 없음 (파라미터 없음) |

- 새 오류 코드 없음. 출고 API 의 응답 코드는 그대로 (200, 400, 404, 409)
- 이벤트 변경 없음

### 테이블 변경 (inventory V9)
| 테이블 | 변경 |
|---|---|
| `storage_slot` 새로 | id, code(VARCHAR(10) UNIQUE), zone(VARCHAR(10)), finished_vehicle_id(BIGINT NULL UNIQUE, 논리 FK), version, created_at, updated_at. 40줄 INSERT |
| `finished_vehicle` | `slot_code` VARCHAR(10) NULL 추가 (자리 대기면 NULL, 출고 뒤에도 남김) |

- 이미 있는 IN_STOCK 완성차는 마이그레이션에서 완성차 id 순으로 자리를 채운다 (40대 넘으면 나머지는 자리 대기)

## 관련 이벤트 (events.md 참조)
없음. `production.completed`, `shipment.completed` payload 그대로.

## 완료 조건 (테스트로 확인할 것)
- [ ] production.completed 수신 → 완성차 IN_STOCK + 빈 자리 중 코드 순 첫 자리, 자리에 그 차
- [ ] 자리가 다 차면 자리 없이 IN_STOCK (완성은 성공)
- [ ] 출고 → 자리 비움, 완성차 slot_code 는 남음
- [ ] 출고로 자리가 나면 자리 대기 중 가장 먼저 완성된 차가 들어감
- [ ] 같은 이벤트 두 번 → 자리 한 번만
- [ ] 두 차가 동시에 완성돼도 같은 자리에 두 대가 서지 않음
- [ ] 완성차 목록·출고 응답에 slotCode
- [ ] 자리 현황: 40자리 코드 순, occupied, waitingVehicles. 업무 역할 3개 조회 가능 (gateway)
- [ ] V9 마이그레이션 (자리 40줄, 기존 IN_STOCK 차 자리 채움), MigrationTest
- [ ] Swagger: 자리 현황 summary·응답 코드, 응답 DTO 의 slotCode 설명
