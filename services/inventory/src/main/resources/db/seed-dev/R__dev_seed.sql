-- dev 테스트 데이터 (#34). dev 설정의 spring.flyway.locations 에 classpath:db/seed-dev 를 넣은 환경에서만 적용된다.
-- 반복 마이그레이션(R__): 파일이 바뀔 때마다 다시 실행되므로, 같은 키는 갱신만 하고 중복으로 넣지 않는다.

-- 부품과 재고. id 를 고정해 production 시드의 BOM(part_id) 과 맞춘다
INSERT INTO part (id, code, name, unit, available, created_at, updated_at) VALUES
    (1001, 'ENG-G16', '가솔린 엔진 1.6', 'EA', 300, NOW(6), NOW(6)),
    (1002, 'ENG-G25T', '가솔린 엔진 2.5 터보', 'EA', 200, NOW(6), NOW(6)),
    (1003, 'ENG-G35', '가솔린 엔진 3.5', 'EA', 150, NOW(6), NOW(6)),
    (1004, 'ENG-D22', '디젤 엔진 2.2', 'EA', 150, NOW(6), NOW(6)),
    (1005, 'HEV-PT', '하이브리드 파워트레인', 'SET', 200, NOW(6), NOW(6)),
    (1006, 'EV-MOTOR', '구동 모터', 'EA', 250, NOW(6), NOW(6)),
    (1007, 'EV-BATTERY', '고전압 배터리 팩', 'EA', 200, NOW(6), NOW(6)),
    (1008, 'FC-STACK', '수소 연료전지 스택', 'EA', 50, NOW(6), NOW(6)),
    (1009, 'TM-8AT', '8단 자동변속기', 'EA', 300, NOW(6), NOW(6)),
    (1010, 'TM-DCT', '듀얼 클러치 변속기', 'EA', 150, NOW(6), NOW(6)),
    (1011, 'TIRE-16', '타이어 16인치', 'EA', 2000, NOW(6), NOW(6)),
    (1012, 'TIRE-18', '타이어 18인치', 'EA', 2000, NOW(6), NOW(6)),
    (1013, 'TIRE-20', '타이어 20인치', 'EA', 1500, NOW(6), NOW(6)),
    (1014, 'SEAT-SET', '시트 세트', 'SET', 500, NOW(6), NOW(6)),
    (1015, 'BODY-SEDAN', '세단 차체', 'EA', 200, NOW(6), NOW(6)),
    (1016, 'BODY-SUV', 'SUV 차체', 'EA', 200, NOW(6), NOW(6)),
    (1017, 'BODY-VAN', '승합·상용 차체', 'EA', 100, NOW(6), NOW(6)),
    (1018, 'ECU', '차량 제어기', 'EA', 600, NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE name = VALUES(name), unit = VALUES(unit), is_deleted = FALSE;
