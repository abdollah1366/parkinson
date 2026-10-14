#!/bin/sh
# Runs the server on this computer (Linux or macOS), for testing on the local network. Needs Java 21; the database is built in (local-data folder).
# The values below are for testing only. A real server uses docker-compose.yml with its own secrets.
cd "$(dirname "$0")"
export PORT=8090
export EMBEDDED_DB_DIR=local-data
export JWT_SECRET=local-test-only-local-test-only-local-test-only
export NATIONAL_ID_PEPPER=local-test-only-pepper-local-test-only-pepper
export ADMIN_MOBILE=09120000000
export ADMIN_PASSWORD=admin12345
export ADMIN_NAME=Admin
echo "Panel: http://localhost:8090  (admin: 09120000000 / admin12345)"
exec ./gradlew run --console=plain
