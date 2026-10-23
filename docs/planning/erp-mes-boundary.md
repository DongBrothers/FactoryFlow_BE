# ERP · MES · 설비 경계

새 기능을 어느 서비스에 둘지 정할 때 보는 기준이다. 제조업 표준 계층(ISA-95)으로 나눈다.

## 계층과 서비스
| 계층 | 이름 | 하는 일 | 시간 단위 | 서비스 |
|---|---|---|---|---|
| Level 4 | ERP (계획·관리) | 고객·주문, 구매·입고, 부품·완성차 재고, 기준 정보(제품·BOM·부품·공급업체), 생산 계획(차 한 대씩 생산 건, 작업지시 발행 결정), 출고 | 일·주 | order, purchase, inventory, production |
| Level 3 | MES (현장 실행) | 작업지시 실행, 설비 배정, 공정 진행·실적, 품질(양품·불량·폐기), 설비 상태, VIN, 공정 센서값 | 분·초 | mes |
| Level 2 | 설비 (제어) | 실제 작업, 시작·완료·상태 보고, 센서값 | 초 이하 | simulator (프레스기·로봇을 흉내 내는 장치) |

- 다른 서비스는 업무 이름(주문, 재고, 구매, 생산 계획)인데 mes 만 시스템 이름이다. "현장 실행" 영역을 부르는 업계 표준 이름이라 그대로 쓴다
- simulator 는 MES 가 아니다. MES 가 시키는 일을 하고 보고하는 설비 자리다. 업무 판단을 하지 않고 DB 도 없다

## 경계: 작업지시 발행
```
ERP (production)  ── production.released ──────────────▶  MES (mes)  ── mes.step-dispatched ──▶  설비 (simulator)
                  ◀─ mes.production-started ───────────              ◀─ equipment.* ─────────
                  ◀─ mes.vehicle-completed (VIN) ─────
                  ── production.release-cancel-requested ▶
                  ◀─ mes.work-order-cancelled / cancel-rejected
```
- ERP 는 "무엇을, 몇 대, 언제" 를 정해 작업지시를 내려보낸다. 발행 뒤의 진행과 취소 판단(첫 공정을 시작했는가)은 MES 가 한다
- MES 는 결과(시작, 완성, 취소 여부)만 ERP 에 올려보낸다

## ERP 가 MES 정보를 보는 방식
- ERP 는 MES 의 결과를 이벤트로 받아 **요약 상태**만 갖는다: 생산 건 상태(READY → IN_PROGRESS → COMPLETED), 고객에게 보이는 차량 상태, 완성차 VIN
- 공정별 진행, 설비, 실적, 센서값 같은 **세부**는 mes 만 갖고 mes API(`/api/mes/**`)로 본다. 화면은 두 API 를 함께 보여 준다
- ERP 가 MES 테이블을 직접 읽거나 세부를 복사해 두지 않는다

## 회색 지대
| 기능 | 위치 | 이유 |
|---|---|---|
| 작업지시 발행 결정 | production (ERP) | 계획의 마지막 단계. 발행 대기 목록을 보고 사람이 정한다 |
| VIN 발급 | mes | 검사를 통과하는 현장 시점에 정해진다 |
| 부품 LOT ↔ VIN 추적 (제품 계보) | mes | 의장 현장에서 찍은 LOT 이 계보다 (`work_order_part_scan`, 조회 `/mes/vehicles/{vin}/part-lots`, `/mes/part-lots/{lotNo}/vehicles`). inventory 는 LOT 재고 수량만 줄인다 (찍은 LOT 부터, 안 찍은 부품은 입고 순). 안 찍은 부품은 계보가 없다 (#174, #176, docs/specs/part-genealogy.md) |
| 완성차 보관 위치·출고 | inventory (ERP) | 창고 물류 |
| 공정 센서값 | mes (MongoDB) | 현장 데이터 이력. 업무 상태가 아니라 기록이라 MongoDB 에 둔다 |

## 새 기능을 둘 곳 판단
1. **시간 단위**: 일·주 단위 계획·수량이면 ERP, 분·초 단위 실행이면 MES
2. **대상**: 고객·돈·자재·계획이면 ERP, 차 한 대의 공정·설비·작업자면 MES
3. **데이터 주인**: 그 상태를 바꿀 수 있는 서비스는 하나다. 다른 서비스는 이벤트로 결과를 받는다

## MES 범위
MES 표준 기능(MESA-11) 중 지금 하는 것과 하지 않는 것.
| 기능 | 여부 |
|---|---|
| 작업 배정, 자원(설비) 상태, 데이터 수집(실적·센서값), 제품 추적(작업지시·공정·VIN), 기본 품질(불량·폐기) | 한다 |
| 성과 분석(OEE), 상세 일정(순서 최적화), 보전 관리, 작업자 관리, 문서 관리, 공정 레시피 | 하지 않는다 (2026-10-21 결정, 필요하면 다시 정한다) |
