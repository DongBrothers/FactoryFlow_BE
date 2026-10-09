# DB 연결 장애
증상: 로그에 Communications link failure / HikariPool Connection is not available / Too many connections
확인: aws logs tail /ecs/ff-<svc> --since 30m --filter-pattern '"HikariPool"'
     → DB 접속 후 SHOW PROCESSLIST; / SHOW STATUS LIKE 'Threads_connected';
조치: 커넥션 고갈이면 오래 걸리는 쿼리 확인 후 정리 → aws ecs update-service --cluster ff-cluster --service ff-<svc> --force-new-deployment
     DB 자체 장애면 DB 복구 후 같은 명령으로 서비스 재시작
