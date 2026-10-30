#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/../.." && pwd)"
service="${1:-}"
case "$service" in
    order) password_var=ORDERS_DB_PASSWORD ;;
    account) password_var=ACCOUNTS_DB_PASSWORD ;;
    custody) password_var=CUSTODY_DB_PASSWORD ;;
    settlement) password_var=SETTLEMENTS_DB_PASSWORD ;;
    orchestrator) password_var=ORCHESTRATOR_DB_PASSWORD ;;
    *) echo 'Uso: bash infra/scripts/run-service.sh {order|account|custody|settlement|orchestrator}' >&2; exit 2 ;;
esac
[[ -f "$repo_dir/infra/.env" ]] || { echo 'Prepare infra/.env primeiro.' >&2; exit 1; }
# Arquivo local confiável, compatível com Bash e Compose; nunca usar set -x.
source "$repo_dir/infra/.env"
export DB_PASSWORD="${!password_var:?Senha do banco não definida}"
[[ "$DB_PASSWORD" != CHANGE_ME* && "${RABBITMQ_PASSWORD:?}" != CHANGE_ME* ]] || {
    echo 'Substitua as senhas de exemplo em infra/.env.' >&2; exit 1;
}
export DB_HOST POSTGRES_PORT RABBITMQ_HOST RABBITMQ_PORT RABBITMQ_USER RABBITMQ_PASSWORD RABBITMQ_VHOST
# A JVM recebe apenas a senha de seu banco; não recebe as dos vizinhos/admin.
unset POSTGRES_ADMIN_PASSWORD ORDERS_DB_PASSWORD ACCOUNTS_DB_PASSWORD CUSTODY_DB_PASSWORD SETTLEMENTS_DB_PASSWORD ORCHESTRATOR_DB_PASSWORD
jar="$repo_dir/orquestrada/$service-service/target/$service-service-0.0.1-SNAPSHOT.jar"
[[ -f "$jar" ]] || { echo 'Execute ./mvnw clean verify em orquestrada primeiro.' >&2; exit 1; }
java_bin=java
if [[ -n "${JAVA_HOME:-}" ]]; then java_bin="$JAVA_HOME/bin/java"; fi
exec "$java_bin" -jar "$jar"
