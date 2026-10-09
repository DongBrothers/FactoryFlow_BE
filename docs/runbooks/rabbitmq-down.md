# RabbitMQ 장애
증상: 이벤트 적체, Outbox 미발행 증가
확인: ssh ec2-user@<RABBITMQ_HOST> → docker ps / docker logs rabbitmq --tail 100
조치: docker restart rabbitmq → Outbox 재발행 자동 확인
