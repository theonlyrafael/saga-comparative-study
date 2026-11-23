#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/../.." && pwd)"
# Read-only database assertions use each service's own login in the test process.
source "${SAGA_ENV_FILE:-$repo_dir/infra/.env}"
export DB_HOST POSTGRES_PORT ORDERS_DB_PASSWORD ACCOUNTS_DB_PASSWORD CUSTODY_DB_PASSWORD
export SETTLEMENTS_DB_PASSWORD ORCHESTRATOR_DB_PASSWORD
cd "$repo_dir/orquestrada"
exec ./mvnw -f tests/pom.xml test "$@"
