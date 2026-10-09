# 서비스 5xx 증가
증상: 5xx 알람, 특정 서비스(ff-<svc>) 응답 실패율 증가
확인: aws logs tail /ecs/ff-<svc> --since 30m --filter-pattern ERROR
     → aws ecs describe-services --cluster ff-cluster --services ff-<svc> (runningCount / desiredCount, events, 최근 deployments)
조치: 최근 배포 직후면 이전 태스크 정의로 롤백 → aws ecs update-service --cluster ff-cluster --service ff-<svc> --task-definition ff-<svc>:<이전 revision>
     배포와 무관하면 의존 대상 확인 → db-connection.md / rabbitmq-down.md
