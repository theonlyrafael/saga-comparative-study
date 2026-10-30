#!/usr/bin/env bash
set -euo pipefail
# Convenção de portas do guia. Para portas customizadas, consulte diretamente o endpoint.
for port in 8081 8082 8083 8084 8085; do
    for endpoint in health health/liveness health/readiness prometheus; do
        curl --fail --silent --show-error --max-time 15 "http://127.0.0.1:$port/actuator/$endpoint" >/dev/null
    done
    echo "OK: porta $port, saúde, liveness, readiness (PostgreSQL + RabbitMQ) e Prometheus."
done
