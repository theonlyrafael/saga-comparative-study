#!/usr/bin/env bash
# Shared by black-box checks run from the repository root.
declare -A pids=() ports=()

init_stack() {
    umask 077
    run_dir=$(mktemp -d)
    cp "${SAGA_ENV_FILE:-infra/.env}" "$run_dir/services.env"
    export SAGA_ENV_FILE="$run_dir/services.env"
    project="saga-${1}-${run_dir##*.}"
    compose=(docker compose -p "${project,,}" --env-file "$SAGA_ENV_FILE" -f infra/compose.yaml)
    trap cleanup EXIT
    trap 'exit 130' INT TERM
    POSTGRES_PORT=0 RABBITMQ_PORT=0 RABBITMQ_MANAGEMENT_PORT=0 "${compose[@]}" up -d --wait
    port() { "${compose[@]}" port "$1" "$2" | cut -d: -f2; }
    {
        echo
        echo "POSTGRES_PORT=$(port postgres 5432)"
        echo "RABBITMQ_PORT=$(port rabbitmq 5672)"
        echo "RABBITMQ_MANAGEMENT_PORT=$(port rabbitmq 15672)"
        echo "DB_HOST=127.0.0.1"
        echo "RABBITMQ_HOST=127.0.0.1"
    } >> "$SAGA_ENV_FILE"
    source "$SAGA_ENV_FILE"
    export RABBITMQ_USER RABBITMQ_PASSWORD RABBITMQ_VHOST RABBITMQ_MANAGEMENT_PORT
    python3 infra/rabbitmq/configure.py
}

stop() {
    local svc=$1
    kill "-${2:-TERM}" "${pids[$svc]}"
    wait "${pids[$svc]}" 2>/dev/null || true
    unset 'pids[$svc]'
}
cleanup() {
    local status=$? svc
    for svc in "${!pids[@]}"; do stop "$svc" || true; done
    "${compose[@]}" down -v >/dev/null
    if (( status == 0 )); then rm -r -- "$run_dir"
    else rm -f -- "$SAGA_ENV_FILE"; echo "Logs: $run_dir" >&2; fi
}

start() {
    local svc=$1 profile=default
    case "$svc" in order|custody|account) profile=demo;; esac
    SPRING_PROFILES_ACTIVE="$profile" SERVER_PORT=0 bash infra/scripts/run-service.sh "$svc" >"$run_dir/$svc.log" 2>&1 &
    pids[$svc]=$!
}
ready() {
    local svc=$1 deadline=$((SECONDS + 150))
    while (( SECONDS < deadline )) && kill -0 "${pids[$svc]}" 2>/dev/null; do
        ports[$svc]=$(sed -nE 's/.*Tomcat started on port ([0-9]+).*/\1/p' "$run_dir/$svc.log" | tail -1)
        if [[ -n ${ports[$svc]} ]] && curl -fsS --max-time 3 "http://127.0.0.1:${ports[$svc]}/actuator/health/readiness" >/dev/null 2>&1; then return; fi
        sleep 1
    done
    echo "Readiness failed for $svc" >&2; return 1
}
sql() {
    "${compose[@]}" exec -T postgres psql -XAtq -v ON_ERROR_STOP=1 -U "${1}_user" -d "${1}_db" -c "$2"
}
await_sql() {
    local deadline=$((SECONDS + 90)) value
    while (( SECONDS < deadline )); do
        value=$(sql "$1" "$2")
        if [[ $value == "$3" ]]; then return; fi
        sleep 1
    done
    echo "$1: expected '$3', got '$value' for $2" >&2; return 1
}
