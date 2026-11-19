#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
set -a
source infra/.env
set +a
python3 infra/rabbitmq/configure.py "$@"
