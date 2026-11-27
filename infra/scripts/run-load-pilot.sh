#!/usr/bin/env bash
set -euo pipefail
if (( $# != 1 )); then echo "Uso: $0 <diretório novo para os resultados>" >&2; exit 2; fi
mkdir -- "$1"
output=$(cd "$1" && pwd)
cd "$(dirname "$0")/../.."
source infra/scripts/lib/saga-test-stack.sh
export COMPOSE_PROFILES=metrics
prepare_stack pilot infra/pilot/compose.yaml
export PILOT_DB_INIT="$run_dir/init.sql"
install -m 644 infra/pilot/init.sql "$PILOT_DB_INIT"
export PILOT_PROM_CONFIG="$run_dir/prometheus.yml"
export JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=2 -Xms48m -Xmx256m'
export OUTBOX_POLL_INTERVAL=1s SAGA_TIMEOUT_STEP=30s RECONCILIATION_POLL_INTERVAL=5s
export SAGA_RETRY_MAX_ATTEMPTS=5 SAGA_RETRY_INITIAL_DELAY=1s SAGA_RETRY_MAX_DELAY=1m
export DB_POOL_MAX_SIZE=5 DB_POOL_MIN_IDLE=1 RABBITMQ_PREFETCH=10
export SAGA_TRACE_SAMPLING=1.0 SAGA_TRACE_EXPORT=false OUTBOX_PUBLISHER_ENABLED=true
unset SETTLEMENT_SIMULATION_DENY_ORDER_ID

services=(order account custody settlement orchestrator)
databases=(orders accounts custody settlements orchestrator)
RABBITMQ_PORT=0 RABBITMQ_MANAGEMENT_PORT=0 "${compose[@]}" up -d --wait "${databases[@]}" rabbitmq
connect_broker
for i in "${!services[@]}"; do
    db=${databases[i]}
    printf '%s\t%s\n' "$db" "$("${compose[@]}" ps -q "$db")" >> "$run_dir/containers.tsv"
    SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:$(port "$db" 5432)/${db}_db" start "${services[i]}" default
done
printf 'rabbitmq\t%s\n' "$("${compose[@]}" ps -q rabbitmq)" >> "$run_dir/containers.tsv"
{
    echo 'global: {scrape_interval: 2s, scrape_timeout: 1800ms}'
    echo 'scrape_configs:'
    echo '  - job_name: pilot'
    echo '    metrics_path: /actuator/prometheus'
    echo '    static_configs:'
    for svc in "${services[@]}"; do
        ready "$svc"
        printf '      - targets: ["127.0.0.1:%s"]\n        labels: {service: "%s"}\n' "${ports[$svc]}" "$svc"
    done
} > "$PILOT_PROM_CONFIG"
chmod 644 "$PILOT_PROM_CONFIG"
"${compose[@]}" up -d --wait prometheus
for ((attempt=0; attempt<30; attempt++)); do
    prom_port=$("${compose[@]}" logs --no-log-prefix prometheus | sed -nE 's/.*msg="Listening on".*address=127\.0\.0\.1:([0-9]+).*/\1/p' | tail -1)
    if [[ -n $prom_port ]] && curl -fsS --max-time 2 "http://127.0.0.1:$prom_port/-/ready" > /dev/null 2>&1; then break; fi
    sleep 1
done
curl -fsS --max-time 3 "http://127.0.0.1:$prom_port/-/ready" > /dev/null
"${compose[@]}" config --images | sort -u > "$run_dir/images.txt"
python3 -B experimentos/piloto/run.py "$output" "$run_dir" \
    "http://127.0.0.1:${ports[order]}/api/ordens" "http://127.0.0.1:$prom_port" \
    "${PILOT_PLAN:-experimentos/piloto/plan.json}"
