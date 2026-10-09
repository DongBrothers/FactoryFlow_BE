> 코멘트만 남긴다 (머지를 막지 않음). 팀원이 배울 수 있게 "왜 문제인지"를 한 줄 덧붙인다.
> Spotless(포맷, 안 쓰는 import), ArchUnit(구조 규칙)이 잡는 것은 보지 않는다.

- 로직 오류 (조건 반대, 경계값, 누락된 분기)
- null / Optional 처리 (Optional.get(), NPE 가능성)
- 예외 처리 (삼키는 catch, 너무 넓은 catch, CustomException/ErrorCode 미사용)
- 트랜잭션 범위 (@Transactional 누락, readOnly 누락, 트랜잭션 안의 외부 호출)
- JPA 성능 (N+1, 불필요한 전체 조회)
- 동시성 (재고 차감 등 경쟁 조건, 락 누락)
- 테스트 품질 (동작을 실제로 검증하는가, 실패 케이스가 있는가)
- 이름과 가독성 (의도가 드러나지 않는 이름, 매직넘버, 너무 긴 메서드)
