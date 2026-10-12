-- dev 테스트 데이터 (#34). dev 설정의 spring.flyway.locations 에 classpath:db/seed-dev 를 넣은 환경에서만 적용된다.
-- 반복 마이그레이션(R__): 파일이 바뀔 때마다 다시 실행되므로, 같은 키는 갱신만 하고 중복으로 넣지 않는다.

-- 제품: 현대자동차 차량 30종. ST1, 엑시언트 수소전기트럭은 BOM 이 없다 (BOM_NOT_FOUND 테스트용)
INSERT INTO product (code, name, created_at, updated_at) VALUES
    ('AVANTE', '아반떼', NOW(6), NOW(6)),
    ('AVANTE-HEV', '아반떼 하이브리드', NOW(6), NOW(6)),
    ('AVANTE-N', '아반떼 N', NOW(6), NOW(6)),
    ('SONATA', '쏘나타 디 엣지', NOW(6), NOW(6)),
    ('SONATA-HEV', '쏘나타 디 엣지 하이브리드', NOW(6), NOW(6)),
    ('GRANDEUR', '그랜저', NOW(6), NOW(6)),
    ('GRANDEUR-HEV', '그랜저 하이브리드', NOW(6), NOW(6)),
    ('CASPER', '캐스퍼', NOW(6), NOW(6)),
    ('CASPER-EV', '캐스퍼 일렉트릭', NOW(6), NOW(6)),
    ('VENUE', '베뉴', NOW(6), NOW(6)),
    ('KONA', '코나', NOW(6), NOW(6)),
    ('KONA-HEV', '코나 하이브리드', NOW(6), NOW(6)),
    ('KONA-EV', '코나 일렉트릭', NOW(6), NOW(6)),
    ('TUCSON', '투싼', NOW(6), NOW(6)),
    ('TUCSON-HEV', '투싼 하이브리드', NOW(6), NOW(6)),
    ('SANTAFE', '싼타페', NOW(6), NOW(6)),
    ('SANTAFE-HEV', '싼타페 하이브리드', NOW(6), NOW(6)),
    ('PALISADE', '팰리세이드', NOW(6), NOW(6)),
    ('PALISADE-HEV', '팰리세이드 하이브리드', NOW(6), NOW(6)),
    ('IONIQ5', '아이오닉 5', NOW(6), NOW(6)),
    ('IONIQ5-N', '아이오닉 5 N', NOW(6), NOW(6)),
    ('IONIQ6', '아이오닉 6', NOW(6), NOW(6)),
    ('IONIQ9', '아이오닉 9', NOW(6), NOW(6)),
    ('NEXO', '넥쏘', NOW(6), NOW(6)),
    ('STARIA', '스타리아', NOW(6), NOW(6)),
    ('STARIA-LOUNGE', '스타리아 라운지', NOW(6), NOW(6)),
    ('PORTER2', '포터 II', NOW(6), NOW(6)),
    ('PORTER2-EV', '포터 II 일렉트릭', NOW(6), NOW(6)),
    ('ST1', 'ST1', NOW(6), NOW(6)),
    ('XCIENT-FCEV', '엑시언트 수소전기트럭', NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE name = VALUES(name), is_deleted = FALSE;

-- BOM: 제품 1대에 필요한 부품과 수량. part_id 는 inventory 시드의 부품 id (1001~1018)
INSERT INTO bom (product_id, part_id, qty_per_unit, created_at, updated_at)
SELECT p.id, b.part_id, b.qty, NOW(6), NOW(6)
FROM product p
JOIN (    SELECT 'AVANTE' AS code, 1001 AS part_id, 1 AS qty
          UNION ALL SELECT 'AVANTE' AS code, 1009 AS part_id, 1 AS qty
          UNION ALL SELECT 'AVANTE' AS code, 1011 AS part_id, 4 AS qty
          UNION ALL SELECT 'AVANTE' AS code, 1015 AS part_id, 1 AS qty
          UNION ALL SELECT 'AVANTE' AS code, 1014 AS part_id, 1 AS qty
          UNION ALL SELECT 'AVANTE' AS code, 1018 AS part_id, 1 AS qty
          UNION ALL SELECT 'AVANTE-HEV' AS code, 1001 AS part_id, 1 AS qty
          UNION ALL SELECT 'AVANTE-HEV' AS code, 1005 AS part_id, 1 AS qty
          UNION ALL SELECT 'AVANTE-HEV' AS code, 1009 AS part_id, 1 AS qty
          UNION ALL SELECT 'AVANTE-HEV' AS code, 1011 AS part_id, 4 AS qty
          UNION ALL SELECT 'AVANTE-HEV' AS code, 1015 AS part_id, 1 AS qty
          UNION ALL SELECT 'AVANTE-HEV' AS code, 1014 AS part_id, 1 AS qty
          UNION ALL SELECT 'AVANTE-HEV' AS code, 1018 AS part_id, 1 AS qty
          UNION ALL SELECT 'AVANTE-N' AS code, 1002 AS part_id, 1 AS qty
          UNION ALL SELECT 'AVANTE-N' AS code, 1010 AS part_id, 1 AS qty
          UNION ALL SELECT 'AVANTE-N' AS code, 1012 AS part_id, 4 AS qty
          UNION ALL SELECT 'AVANTE-N' AS code, 1015 AS part_id, 1 AS qty
          UNION ALL SELECT 'AVANTE-N' AS code, 1014 AS part_id, 1 AS qty
          UNION ALL SELECT 'AVANTE-N' AS code, 1018 AS part_id, 1 AS qty
          UNION ALL SELECT 'SONATA' AS code, 1002 AS part_id, 1 AS qty
          UNION ALL SELECT 'SONATA' AS code, 1009 AS part_id, 1 AS qty
          UNION ALL SELECT 'SONATA' AS code, 1012 AS part_id, 4 AS qty
          UNION ALL SELECT 'SONATA' AS code, 1015 AS part_id, 1 AS qty
          UNION ALL SELECT 'SONATA' AS code, 1014 AS part_id, 1 AS qty
          UNION ALL SELECT 'SONATA' AS code, 1018 AS part_id, 1 AS qty
          UNION ALL SELECT 'SONATA-HEV' AS code, 1001 AS part_id, 1 AS qty
          UNION ALL SELECT 'SONATA-HEV' AS code, 1005 AS part_id, 1 AS qty
          UNION ALL SELECT 'SONATA-HEV' AS code, 1009 AS part_id, 1 AS qty
          UNION ALL SELECT 'SONATA-HEV' AS code, 1012 AS part_id, 4 AS qty
          UNION ALL SELECT 'SONATA-HEV' AS code, 1015 AS part_id, 1 AS qty
          UNION ALL SELECT 'SONATA-HEV' AS code, 1014 AS part_id, 1 AS qty
          UNION ALL SELECT 'SONATA-HEV' AS code, 1018 AS part_id, 1 AS qty
          UNION ALL SELECT 'GRANDEUR' AS code, 1002 AS part_id, 1 AS qty
          UNION ALL SELECT 'GRANDEUR' AS code, 1009 AS part_id, 1 AS qty
          UNION ALL SELECT 'GRANDEUR' AS code, 1012 AS part_id, 4 AS qty
          UNION ALL SELECT 'GRANDEUR' AS code, 1015 AS part_id, 1 AS qty
          UNION ALL SELECT 'GRANDEUR' AS code, 1014 AS part_id, 1 AS qty
          UNION ALL SELECT 'GRANDEUR' AS code, 1018 AS part_id, 1 AS qty
          UNION ALL SELECT 'GRANDEUR-HEV' AS code, 1001 AS part_id, 1 AS qty
          UNION ALL SELECT 'GRANDEUR-HEV' AS code, 1005 AS part_id, 1 AS qty
          UNION ALL SELECT 'GRANDEUR-HEV' AS code, 1009 AS part_id, 1 AS qty
          UNION ALL SELECT 'GRANDEUR-HEV' AS code, 1012 AS part_id, 4 AS qty
          UNION ALL SELECT 'GRANDEUR-HEV' AS code, 1015 AS part_id, 1 AS qty
          UNION ALL SELECT 'GRANDEUR-HEV' AS code, 1014 AS part_id, 1 AS qty
          UNION ALL SELECT 'GRANDEUR-HEV' AS code, 1018 AS part_id, 1 AS qty
          UNION ALL SELECT 'CASPER' AS code, 1001 AS part_id, 1 AS qty
          UNION ALL SELECT 'CASPER' AS code, 1009 AS part_id, 1 AS qty
          UNION ALL SELECT 'CASPER' AS code, 1011 AS part_id, 4 AS qty
          UNION ALL SELECT 'CASPER' AS code, 1016 AS part_id, 1 AS qty
          UNION ALL SELECT 'CASPER' AS code, 1014 AS part_id, 1 AS qty
          UNION ALL SELECT 'CASPER' AS code, 1018 AS part_id, 1 AS qty
          UNION ALL SELECT 'CASPER-EV' AS code, 1006 AS part_id, 1 AS qty
          UNION ALL SELECT 'CASPER-EV' AS code, 1007 AS part_id, 1 AS qty
          UNION ALL SELECT 'CASPER-EV' AS code, 1011 AS part_id, 4 AS qty
          UNION ALL SELECT 'CASPER-EV' AS code, 1016 AS part_id, 1 AS qty
          UNION ALL SELECT 'CASPER-EV' AS code, 1014 AS part_id, 1 AS qty
          UNION ALL SELECT 'CASPER-EV' AS code, 1018 AS part_id, 1 AS qty
          UNION ALL SELECT 'VENUE' AS code, 1001 AS part_id, 1 AS qty
          UNION ALL SELECT 'VENUE' AS code, 1009 AS part_id, 1 AS qty
          UNION ALL SELECT 'VENUE' AS code, 1011 AS part_id, 4 AS qty
          UNION ALL SELECT 'VENUE' AS code, 1016 AS part_id, 1 AS qty
          UNION ALL SELECT 'VENUE' AS code, 1014 AS part_id, 1 AS qty
          UNION ALL SELECT 'VENUE' AS code, 1018 AS part_id, 1 AS qty
          UNION ALL SELECT 'KONA' AS code, 1001 AS part_id, 1 AS qty
          UNION ALL SELECT 'KONA' AS code, 1009 AS part_id, 1 AS qty
          UNION ALL SELECT 'KONA' AS code, 1012 AS part_id, 4 AS qty
          UNION ALL SELECT 'KONA' AS code, 1016 AS part_id, 1 AS qty
          UNION ALL SELECT 'KONA' AS code, 1014 AS part_id, 1 AS qty
          UNION ALL SELECT 'KONA' AS code, 1018 AS part_id, 1 AS qty
          UNION ALL SELECT 'KONA-HEV' AS code, 1001 AS part_id, 1 AS qty
          UNION ALL SELECT 'KONA-HEV' AS code, 1005 AS part_id, 1 AS qty
          UNION ALL SELECT 'KONA-HEV' AS code, 1009 AS part_id, 1 AS qty
          UNION ALL SELECT 'KONA-HEV' AS code, 1012 AS part_id, 4 AS qty
          UNION ALL SELECT 'KONA-HEV' AS code, 1016 AS part_id, 1 AS qty
          UNION ALL SELECT 'KONA-HEV' AS code, 1014 AS part_id, 1 AS qty
          UNION ALL SELECT 'KONA-HEV' AS code, 1018 AS part_id, 1 AS qty
          UNION ALL SELECT 'KONA-EV' AS code, 1006 AS part_id, 1 AS qty
          UNION ALL SELECT 'KONA-EV' AS code, 1007 AS part_id, 1 AS qty
          UNION ALL SELECT 'KONA-EV' AS code, 1012 AS part_id, 4 AS qty
          UNION ALL SELECT 'KONA-EV' AS code, 1016 AS part_id, 1 AS qty
          UNION ALL SELECT 'KONA-EV' AS code, 1014 AS part_id, 1 AS qty
          UNION ALL SELECT 'KONA-EV' AS code, 1018 AS part_id, 1 AS qty
          UNION ALL SELECT 'TUCSON' AS code, 1001 AS part_id, 1 AS qty
          UNION ALL SELECT 'TUCSON' AS code, 1009 AS part_id, 1 AS qty
          UNION ALL SELECT 'TUCSON' AS code, 1012 AS part_id, 4 AS qty
          UNION ALL SELECT 'TUCSON' AS code, 1016 AS part_id, 1 AS qty
          UNION ALL SELECT 'TUCSON' AS code, 1014 AS part_id, 1 AS qty
          UNION ALL SELECT 'TUCSON' AS code, 1018 AS part_id, 1 AS qty
          UNION ALL SELECT 'TUCSON-HEV' AS code, 1001 AS part_id, 1 AS qty
          UNION ALL SELECT 'TUCSON-HEV' AS code, 1005 AS part_id, 1 AS qty
          UNION ALL SELECT 'TUCSON-HEV' AS code, 1009 AS part_id, 1 AS qty
          UNION ALL SELECT 'TUCSON-HEV' AS code, 1012 AS part_id, 4 AS qty
          UNION ALL SELECT 'TUCSON-HEV' AS code, 1016 AS part_id, 1 AS qty
          UNION ALL SELECT 'TUCSON-HEV' AS code, 1014 AS part_id, 1 AS qty
          UNION ALL SELECT 'TUCSON-HEV' AS code, 1018 AS part_id, 1 AS qty
          UNION ALL SELECT 'SANTAFE' AS code, 1002 AS part_id, 1 AS qty
          UNION ALL SELECT 'SANTAFE' AS code, 1009 AS part_id, 1 AS qty
          UNION ALL SELECT 'SANTAFE' AS code, 1013 AS part_id, 4 AS qty
          UNION ALL SELECT 'SANTAFE' AS code, 1016 AS part_id, 1 AS qty
          UNION ALL SELECT 'SANTAFE' AS code, 1014 AS part_id, 1 AS qty
          UNION ALL SELECT 'SANTAFE' AS code, 1018 AS part_id, 1 AS qty
          UNION ALL SELECT 'SANTAFE-HEV' AS code, 1001 AS part_id, 1 AS qty
          UNION ALL SELECT 'SANTAFE-HEV' AS code, 1005 AS part_id, 1 AS qty
          UNION ALL SELECT 'SANTAFE-HEV' AS code, 1009 AS part_id, 1 AS qty
          UNION ALL SELECT 'SANTAFE-HEV' AS code, 1013 AS part_id, 4 AS qty
          UNION ALL SELECT 'SANTAFE-HEV' AS code, 1016 AS part_id, 1 AS qty
          UNION ALL SELECT 'SANTAFE-HEV' AS code, 1014 AS part_id, 1 AS qty
          UNION ALL SELECT 'SANTAFE-HEV' AS code, 1018 AS part_id, 1 AS qty
          UNION ALL SELECT 'PALISADE' AS code, 1003 AS part_id, 1 AS qty
          UNION ALL SELECT 'PALISADE' AS code, 1009 AS part_id, 1 AS qty
          UNION ALL SELECT 'PALISADE' AS code, 1013 AS part_id, 4 AS qty
          UNION ALL SELECT 'PALISADE' AS code, 1016 AS part_id, 1 AS qty
          UNION ALL SELECT 'PALISADE' AS code, 1014 AS part_id, 2 AS qty
          UNION ALL SELECT 'PALISADE' AS code, 1018 AS part_id, 1 AS qty
          UNION ALL SELECT 'PALISADE-HEV' AS code, 1001 AS part_id, 1 AS qty
          UNION ALL SELECT 'PALISADE-HEV' AS code, 1005 AS part_id, 1 AS qty
          UNION ALL SELECT 'PALISADE-HEV' AS code, 1009 AS part_id, 1 AS qty
          UNION ALL SELECT 'PALISADE-HEV' AS code, 1013 AS part_id, 4 AS qty
          UNION ALL SELECT 'PALISADE-HEV' AS code, 1016 AS part_id, 1 AS qty
          UNION ALL SELECT 'PALISADE-HEV' AS code, 1014 AS part_id, 2 AS qty
          UNION ALL SELECT 'PALISADE-HEV' AS code, 1018 AS part_id, 1 AS qty
          UNION ALL SELECT 'IONIQ5' AS code, 1006 AS part_id, 1 AS qty
          UNION ALL SELECT 'IONIQ5' AS code, 1007 AS part_id, 1 AS qty
          UNION ALL SELECT 'IONIQ5' AS code, 1013 AS part_id, 4 AS qty
          UNION ALL SELECT 'IONIQ5' AS code, 1016 AS part_id, 1 AS qty
          UNION ALL SELECT 'IONIQ5' AS code, 1014 AS part_id, 1 AS qty
          UNION ALL SELECT 'IONIQ5' AS code, 1018 AS part_id, 1 AS qty
          UNION ALL SELECT 'IONIQ5-N' AS code, 1006 AS part_id, 2 AS qty
          UNION ALL SELECT 'IONIQ5-N' AS code, 1007 AS part_id, 1 AS qty
          UNION ALL SELECT 'IONIQ5-N' AS code, 1013 AS part_id, 4 AS qty
          UNION ALL SELECT 'IONIQ5-N' AS code, 1016 AS part_id, 1 AS qty
          UNION ALL SELECT 'IONIQ5-N' AS code, 1014 AS part_id, 1 AS qty
          UNION ALL SELECT 'IONIQ5-N' AS code, 1018 AS part_id, 1 AS qty
          UNION ALL SELECT 'IONIQ6' AS code, 1006 AS part_id, 1 AS qty
          UNION ALL SELECT 'IONIQ6' AS code, 1007 AS part_id, 1 AS qty
          UNION ALL SELECT 'IONIQ6' AS code, 1013 AS part_id, 4 AS qty
          UNION ALL SELECT 'IONIQ6' AS code, 1015 AS part_id, 1 AS qty
          UNION ALL SELECT 'IONIQ6' AS code, 1014 AS part_id, 1 AS qty
          UNION ALL SELECT 'IONIQ6' AS code, 1018 AS part_id, 1 AS qty
          UNION ALL SELECT 'IONIQ9' AS code, 1006 AS part_id, 2 AS qty
          UNION ALL SELECT 'IONIQ9' AS code, 1007 AS part_id, 1 AS qty
          UNION ALL SELECT 'IONIQ9' AS code, 1013 AS part_id, 4 AS qty
          UNION ALL SELECT 'IONIQ9' AS code, 1016 AS part_id, 1 AS qty
          UNION ALL SELECT 'IONIQ9' AS code, 1014 AS part_id, 2 AS qty
          UNION ALL SELECT 'IONIQ9' AS code, 1018 AS part_id, 1 AS qty
          UNION ALL SELECT 'NEXO' AS code, 1008 AS part_id, 1 AS qty
          UNION ALL SELECT 'NEXO' AS code, 1006 AS part_id, 1 AS qty
          UNION ALL SELECT 'NEXO' AS code, 1007 AS part_id, 1 AS qty
          UNION ALL SELECT 'NEXO' AS code, 1012 AS part_id, 4 AS qty
          UNION ALL SELECT 'NEXO' AS code, 1016 AS part_id, 1 AS qty
          UNION ALL SELECT 'NEXO' AS code, 1014 AS part_id, 1 AS qty
          UNION ALL SELECT 'NEXO' AS code, 1018 AS part_id, 1 AS qty
          UNION ALL SELECT 'STARIA' AS code, 1003 AS part_id, 1 AS qty
          UNION ALL SELECT 'STARIA' AS code, 1009 AS part_id, 1 AS qty
          UNION ALL SELECT 'STARIA' AS code, 1012 AS part_id, 4 AS qty
          UNION ALL SELECT 'STARIA' AS code, 1017 AS part_id, 1 AS qty
          UNION ALL SELECT 'STARIA' AS code, 1014 AS part_id, 2 AS qty
          UNION ALL SELECT 'STARIA' AS code, 1018 AS part_id, 1 AS qty
          UNION ALL SELECT 'STARIA-LOUNGE' AS code, 1003 AS part_id, 1 AS qty
          UNION ALL SELECT 'STARIA-LOUNGE' AS code, 1009 AS part_id, 1 AS qty
          UNION ALL SELECT 'STARIA-LOUNGE' AS code, 1012 AS part_id, 4 AS qty
          UNION ALL SELECT 'STARIA-LOUNGE' AS code, 1017 AS part_id, 1 AS qty
          UNION ALL SELECT 'STARIA-LOUNGE' AS code, 1014 AS part_id, 2 AS qty
          UNION ALL SELECT 'STARIA-LOUNGE' AS code, 1018 AS part_id, 1 AS qty
          UNION ALL SELECT 'PORTER2' AS code, 1004 AS part_id, 1 AS qty
          UNION ALL SELECT 'PORTER2' AS code, 1009 AS part_id, 1 AS qty
          UNION ALL SELECT 'PORTER2' AS code, 1011 AS part_id, 4 AS qty
          UNION ALL SELECT 'PORTER2' AS code, 1017 AS part_id, 1 AS qty
          UNION ALL SELECT 'PORTER2' AS code, 1014 AS part_id, 1 AS qty
          UNION ALL SELECT 'PORTER2' AS code, 1018 AS part_id, 1 AS qty
          UNION ALL SELECT 'PORTER2-EV' AS code, 1006 AS part_id, 1 AS qty
          UNION ALL SELECT 'PORTER2-EV' AS code, 1007 AS part_id, 1 AS qty
          UNION ALL SELECT 'PORTER2-EV' AS code, 1011 AS part_id, 4 AS qty
          UNION ALL SELECT 'PORTER2-EV' AS code, 1017 AS part_id, 1 AS qty
          UNION ALL SELECT 'PORTER2-EV' AS code, 1014 AS part_id, 1 AS qty
          UNION ALL SELECT 'PORTER2-EV' AS code, 1018 AS part_id, 1 AS qty) b ON b.code = p.code
ON DUPLICATE KEY UPDATE qty_per_unit = b.qty, is_deleted = FALSE;
