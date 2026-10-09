# RabbitMQ 장애
증상: 이벤트 적체, Outbox 미발행 증가
확인: ssh ec2-user@<RABBITMQ_HOST> → docker ps / docker logs rabbitmq --tail 100
     (<RABBITMQ_HOST> 는 GitHub Secret APP_DEV_YML_<SVC> 의 spring.rabbitmq.host 값. 저장소에 IP를 적지 않는다)
조치: docker restart rabbitmq → Outbox 재발행 자동 확인
