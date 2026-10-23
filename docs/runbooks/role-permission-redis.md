# 역할 권한 표 Redis 동기화 배포 (dev, 한 번)
명세: docs/specs/role-permission-admin.md. gateway 가 처음으로 Redis 를 쓴다 (auth 가 이미 쓰는 Redis 와 같은 곳).
**gateway 가 Redis 에 붙지 못하면 권한 표가 비어 공개 경로 말고 모든 요청이 403 이다.** 그래서 1·2번은 PR 머지 전에 끝낸다.

## 0. dev Redis 주소 확인
- auth 의 설정 원본(APP_DEV_YML_AUTH 로 등록한 yml)에서 `spring.data.redis` 아래 값(host, port, 있으면 password·ssl)을 찾는다
- GitHub 시크릿은 등록 뒤 값을 다시 볼 수 없다. 원본 파일이 없으면 AWS 콘솔에서 Redis 가 떠 있는 곳(EC2 인스턴스의 사설 IP 또는 ElastiCache 엔드포인트)을 확인한다

## 1. GitHub 시크릿 APP_DEV_YML_GATEWAY 에 Redis 추가 (PR 머지 전)
- 저장소 → Settings → Secrets and variables → Actions → `APP_DEV_YML_GATEWAY` → Update secret
- 시크릿은 통째로 바뀌므로 **지금 내용 전체 + 아래 줄**을 넣는다. `spring:` 이 이미 있으면 그 아래에 `data:` 를 더한다 (`spring:` 을 두 번 쓰면 뒤 것만 남는다)
  ```yaml
  spring:
    data:
      redis:
        host: <0번의 host, auth 와 같은 값>
        port: 6379
        # auth 설정에 password, ssl 이 있으면 같은 값을 넣는다
  ```

## 2. 보안 그룹: gateway → Redis 6379 허용 (PR 머지 전)
1. ECS → 클러스터 → gateway 서비스 → 네트워킹 탭 → 보안 그룹 ID 를 적어 둔다
2. Redis 쪽 보안 그룹(EC2 인스턴스 또는 ElastiCache 의 보안 그룹) → 인바운드 규칙에서 6379 의 소스를 본다
   - 소스가 1번의 보안 그룹이면 할 일 없음 (서비스들이 같은 보안 그룹 sg-0535230f2f50fab11 을 쓰면 auth 를 위해 이미 열려 있다)
   - 아니면 인바운드 규칙 편집 → 규칙 추가: 유형 사용자 지정 TCP, 포트 6379, 소스 = 1번의 보안 그룹
3. 소스를 0.0.0.0/0 으로 열지 않는다

## 3. PR 머지 → CD
- auth 와 gateway 가 함께 배포된다. gateway 가 먼저 뜨면 잠깐 모두 403 이고, auth 가 뜨면서 표를 발행하면 풀린다

## 4. 확인
- gateway 로그에 `권한 표를 읽지 못함` 이 계속 나오면 1·2번을 다시 본다 (처음 한 번은 auth 보다 먼저 뜬 경우라 괜찮다)
- 로그인 → 아무 조회 API 가 200, 관리자 토큰으로 `GET /api/auth/admin/roles` 에 `permissions` 가 나온다
- 관리자 토큰으로 역할의 권한을 바꾸고(`PUT /api/auth/admin/roles/{code}/permissions`) 그 역할로 바로(몇 초 안) 결과가 바뀌는지 본다. 끝나면 원래 값으로 되돌린다

## 되돌리기
- PR 을 되돌리면 gateway 는 #171 처럼 토큰의 `permissions` 로 판단하고 auth 는 다시 `permissions` 를 넣는다. 테이블 변경은 없어 DB 는 그대로 둔다
- 되돌린 뒤 이미 받은 토큰(`permissions` 없음)은 만료(최대 15분)까지 403 이다. 다시 로그인하면 풀린다
