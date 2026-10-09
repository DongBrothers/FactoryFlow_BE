#!/bin/bash
# 서비스별 DB 생성 + 앱 계정 권한 부여 (최초 기동 시 1회 실행)
set -e
for db in order_db inventory_db purchase_db production_db auth_db; do
  mysql -uroot -p"$MYSQL_ROOT_PASSWORD" <<SQL
CREATE DATABASE IF NOT EXISTS \`$db\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON \`$db\`.* TO '$MYSQL_USER'@'%';
SQL
done
