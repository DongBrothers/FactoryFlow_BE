# simulator 서비스 (가상 공장 설비)
- 역할: 현실의 설비(프레스기, 용접 로봇, 도장 로봇 등)를 흉내 낸다. 업무 판단은 하지 않는다 (docs/planning/production-flow.md)
  - 로봇 공정 배정(production.step-dispatched)을 받으면 작업 시간을 흉내 낸 뒤 시작·완료(가끔 불량)를 보고한다
  - 설비 상태(가동 RUNNING · 정지 STOPPED · 고장 FAULT)를 보낸다
- DB: 없음 (JPA, Flyway 의존성 없음). 설비 10대와 진행 중인 작업은 메모리에 둔다
- 발행: equipment.step-started, equipment.step-completed, equipment.status-changed. Outbox 없이 RabbitMQ 로 바로 발행한다 ("발행은 Outbox 로만" 규칙의 유일한 예외). 작업 보고는 브로커가 받았다고 확인할 때까지 메모리에서 다시 보낸다
- 수신: production.step-dispatched (받은 배정은 메모리에서 중복을 거른다)
- 설비 코드와 공정은 production 의 equipment 와 같은 값을 쓴다 (예: PRESS-01, BODY-02)
- 보상: 없음. 고장이 production.failed → 예약 해제로 이어지는 건 production 이 판단한다
