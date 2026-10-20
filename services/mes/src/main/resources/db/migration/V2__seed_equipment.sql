-- 설비 10대: 공정마다 2대, 모두 가동 중. simulator 의 가상 설비와 같은 코드다 (docs/specs/simulator.md)
INSERT INTO equipment (code, name, process, status, created_at, updated_at) VALUES
    ('PRESS-01', '프레스기 1호', 'PRESS', 'RUNNING', NOW(6), NOW(6)),
    ('PRESS-02', '프레스기 2호', 'PRESS', 'RUNNING', NOW(6), NOW(6)),
    ('BODY-01', '용접 로봇 1호', 'BODY', 'RUNNING', NOW(6), NOW(6)),
    ('BODY-02', '용접 로봇 2호', 'BODY', 'RUNNING', NOW(6), NOW(6)),
    ('PAINT-01', '도장 로봇 1호', 'PAINT', 'RUNNING', NOW(6), NOW(6)),
    ('PAINT-02', '도장 로봇 2호', 'PAINT', 'RUNNING', NOW(6), NOW(6)),
    ('ASSEMBLY-01', '의장 라인 1호', 'ASSEMBLY', 'RUNNING', NOW(6), NOW(6)),
    ('ASSEMBLY-02', '의장 라인 2호', 'ASSEMBLY', 'RUNNING', NOW(6), NOW(6)),
    ('INSPECTION-01', '검사 라인 1호', 'INSPECTION', 'RUNNING', NOW(6), NOW(6)),
    ('INSPECTION-02', '검사 라인 2호', 'INSPECTION', 'RUNNING', NOW(6), NOW(6));
