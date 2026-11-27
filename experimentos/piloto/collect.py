"""Collect independent PostgreSQL snapshots and Prometheus samples for one k6 round."""
import json
import os
import subprocess
import time
from urllib.parse import urlencode
from urllib.request import urlopen


class Stack:
    def __init__(self, container_file):
        self.containers = dict(line.split() for line in container_file.read_text().splitlines())

    def sql(self, service, query, **variables):
        args = ["docker", "exec", "-i", self.containers[service], "psql", "-XAtq",
                "-v", "ON_ERROR_STOP=1", "-U", service + "_user", "-d", service + "_db"]
        for key, value in variables.items():
            args.extend(["-v", f"{key}={value}"])
        return subprocess.run(args, input=query, text=True, capture_output=True,
                              check=True, timeout=20).stdout.strip()

    def rows(self, service, query, **variables):
        output = self.sql(service, f"SELECT row_to_json(r) FROM ({query}) r", **variables)
        return [json.loads(line) for line in output.splitlines()]

    def queues(self):
        output = subprocess.check_output(
            ["docker", "exec", self.containers["rabbitmq"], "rabbitmqctl", "-q", "list_queues",
             "-p", os.environ["RABBITMQ_VHOST"], "name", "messages", "--formatter", "json"],
            text=True, timeout=15)
        return {row["name"]: row["messages"] for row in json.loads(output)}

    def seed(self, buyer, asset, plan):
        units, price = plan["unitsPerRound"], plan["unitPriceCents"]
        self.sql("orders", "INSERT INTO asset_catalog VALUES (:'asset','pilot-v1',:cents/100.0)",
                 asset=asset, cents=price)
        self.sql("accounts", "INSERT INTO accounts(account_id,available_balance) VALUES (:'buyer',:cents/100.0)",
                 buyer=buyer, cents=units * price)
        self.sql("custody", """INSERT INTO positions(position_id,owner_id,asset_id,available_quantity)
                 VALUES (gen_random_uuid(),'COUNTERPARTY',:'asset',:units)""", asset=asset, units=units)

    def drain(self, buyer, expected, timeout):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            count = self.sql("orchestrator", """SELECT count(*) FROM saga_instances
                WHERE snapshot->>'clienteId'=:'buyer' AND state IN ('CONCLUIDA','REJEITADA')""", buyer=buyer)
            if int(count) == expected:
                pending = [int(self.sql(db, "SELECT count(*) FROM outbox_messages WHERE published_at IS NULL"))
                           for db in self.containers if db != "rabbitmq"]
                queues = self.queues()
                if not any(pending) and queues and not any(queues.values()):
                    return True
            time.sleep(1)
        return False

    def snapshot(self, buyer, asset):
        # Each query runs with the login of its own service. Correlate the exported IDs offline.
        return {
            "orders": self.rows("orders", """SELECT o.order_id, o.saga_id, o.settlement_id, o.status,
                o.ativo_id AS asset, o.catalog_version, o.quantidade AS qty, (o.total*100)::bigint AS cents,
                (extract(epoch FROM o.created_at)*1000000)::bigint AS created_us, h.idempotency_key AS key
                FROM orders o LEFT JOIN http_idempotency h USING(order_id)
                WHERE o.cliente_id=:'buyer' ORDER BY o.created_at""", buyer=buyer),
            "sagas": self.rows("orchestrator", """SELECT s.order_id, s.saga_id, s.state,
                (extract(epoch FROM s.order_received_at)*1000000)::bigint AS received_us,
                (extract(epoch FROM s.updated_at)*1000000)::bigint AS terminal_us,
                count(t.operation_id) AS steps, count(t.completed_at) AS completed, sum(t.retry_count) AS retries
                FROM saga_instances s LEFT JOIN saga_steps t USING(saga_id)
                WHERE s.snapshot->>'clienteId'=:'buyer' GROUP BY s.saga_id""", buyer=buyer),
            "cash": self.rows("accounts", """SELECT order_id, saga_id, reservation_id, status,
                (amount*100)::bigint AS cents FROM balance_reservations WHERE account_id=:'buyer'""", buyer=buyer),
            "assets": self.rows("custody", """SELECT order_id, saga_id, reservation_id, status,
                asset_id AS asset, quantity AS qty FROM asset_reservations WHERE buyer_id=:'buyer'""", buyer=buyer),
            "settlements": self.rows("settlements", """SELECT order_id, saga_id, settlement_id, status,
                balance_reservation_id AS cash_id, asset_reservation_id AS asset_id_reserved,
                asset_id AS asset, quantity AS qty, (amount*100)::bigint AS cents, catalog_version
                FROM settlements WHERE buyer_id=:'buyer'""", buyer=buyer),
            "account": self.rows("accounts", """SELECT (available_balance*100)::bigint AS available,
                (reserved_balance*100)::bigint AS reserved, (total_consumed*100)::bigint AS consumed
                FROM accounts WHERE account_id=:'buyer'""", buyer=buyer),
            "positions": self.rows("custody", """SELECT owner_id AS owner, available_quantity AS available,
                reserved_quantity AS reserved FROM positions WHERE asset_id=:'asset' ORDER BY owner_id""", asset=asset),
        }


def resource_samples(prometheus, start, end):
    queries = {"cpuFraction": 'process_cpu_usage{job="pilot"}',
               "heapBytes": 'sum by(service) (jvm_memory_used_bytes{job="pilot",area="heap"})'}
    samples = {}
    for name, query in queries.items():
        args = urlencode({"query": query, "start": start, "end": end - 0.001, "step": 2})
        with urlopen(f"{prometheus}/api/v1/query_range?{args}", timeout=10) as response:
            result = json.load(response)
        if result["status"] != "success":
            raise RuntimeError(f"Prometheus query failed for {name}")
        samples[name] = {series["metric"]["service"]: series["values"] for series in result["data"]["result"]}
    return samples
