#!/usr/bin/env bash
set -euo pipefail
infra_dir="$(cd "$(dirname "$0")/.." && pwd)"
docker compose --env-file "$infra_dir/.env" -f "$infra_dir/compose.yaml" exec -T postgres bash -s <<'CHECK'
set -euo pipefail
export PGCONNECT_TIMEOUT=5
services=(orders accounts custody settlements orchestrator)
for service in "${services[@]}"; do
    password_var="${service^^}_DB_PASSWORD"
    export PGPASSWORD="${!password_var}"
    user="${service}_user"
    db="${service}_db"
    result=$(psql -X -w -h 127.0.0.1 -U "$user" -d "$db" -At -v ON_ERROR_STOP=1 \
        -c 'SELECT current_user, current_database()')
    [[ "$result" == "$user|$db" ]]
    flags=$(psql -X -w -h 127.0.0.1 -U "$user" -d "$db" -At -v ON_ERROR_STOP=1 \
        -c 'SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls FROM pg_roles WHERE rolname = current_user')
    [[ "$flags" == f ]]
    # Exercita DDL/DML necessário ao Flyway sem deixar tabela de teste.
    psql -X -w -h 127.0.0.1 -U "$user" -d "$db" -q -v ON_ERROR_STOP=1 \
        -c 'BEGIN; CREATE TABLE public.bootstrap_isolation_probe (id integer); INSERT INTO public.bootstrap_isolation_probe VALUES (1); ROLLBACK;'
    if PGPASSWORD=invalid-bootstrap-password psql -X -w -h 127.0.0.1 -U "$user" -d "$db" -c 'SELECT 1' >/dev/null 2>&1; then
        echo "FALHA: senha inválida aceita para $user" >&2; exit 1
    fi
    for target in "${services[@]}"; do
        [[ "$target" == "$service" ]] && continue
        if result=$(psql -X -w -h 127.0.0.1 -U "$user" -d "${target}_db" -c 'SELECT 1' 2>&1); then
            echo "FALHA: acesso cruzado $user -> ${target}_db" >&2; exit 1
        fi
        # Falha deve ser de autorização, não rede, indisponibilidade ou senha.
        [[ "$result" == *'permission denied for database'* ]] || { echo "$result" >&2; exit 1; }
    done
    echo "OK: $user acessa $db; DDL transacional permitido, senha inválida e quatro bancos vizinhos rejeitados."
done
echo 'Isolamento validado: 5 acessos próprios e 20 recusas de acesso cruzado.'
CHECK
