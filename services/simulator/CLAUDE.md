# simulator 서비스 (가상 공장 설비)
- 역할: 설비 상태만 발생시킨다 (가동 RUNNING · 정지 STOPPED · 고장 FAULT). production API 를 부르지 않는다 (docs/planning/production-flow.md)
- DB: simulator_db. 이벤트를 Outbox(common-event)로 발행하기 위한 outbox 와, 설비 현재 상태만 둔다
- 발행: equipment.status-changed (equipmentCode, process, status)
- 수신: 없음
- 설비 코드와 공정은 production 의 equipment 와 같은 값을 쓴다
- 보상: 없음. 고장이 production.failed → 예약 해제로 이어지는 건 production 이 처리한다
