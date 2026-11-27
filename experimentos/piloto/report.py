"""Rebuild all derived pilot metrics from the archived round samples."""
import gzip
import hashlib
import json
import math
import statistics
import sys
from pathlib import Path

SERVICES = {"order", "account", "custody", "settlement", "orchestrator"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def percentile(ordered, fraction):
    rank = (len(ordered) - 1) * fraction
    low, high = math.floor(rank), math.ceil(rank)
    return ordered[low] + (rank - low) * (ordered[high] - ordered[low])


def verify_round(raw, plan):
    g = raw["generator"]
    count = g["requests"]
    target = raw["rate"] * (plan["warmupSeconds"] + plan["windowSeconds"])
    require(abs(count - target) <= 1 and count == g["receipts"] == g["iterations"]
            and g["dropped"] == 0 and raw["k6Exit"] == 0, f"generator did not deliver the workload: {g}")
    require(raw["drained"], "terminal Sagas, Outboxes and queues did not drain before the deadline")
    data = raw["snapshot"]
    orders = data["orders"]
    keys = {o["order_id"] for o in orders}
    require(len(keys) == len(orders) == count and len({o["saga_id"] for o in orders}) == count
            and all(o["key"] for o in orders) and len({o["key"] for o in orders}) == count,
            "order, Saga or idempotency key count differs from the HTTP receipts")
    by_order = {}
    for name in ("cash", "assets", "settlements", "sagas"):
        rows = data[name]
        index = {r["order_id"]: r for r in rows}
        require(len(rows) == count and index.keys() == keys, f"{name}: missing, extra or duplicate effect")
        by_order[name] = index
    cents, units = plan["unitPriceCents"], plan["unitsPerRound"]
    for o in orders:
        oid = o["order_id"]
        cash, asset, settlement, saga = (by_order[name][oid] for name in ("cash", "assets", "settlements", "sagas"))
        require(all(r["saga_id"] == o["saga_id"] for r in (cash, asset, settlement, saga)), f"{oid}: Saga ID")
        require(o["status"] == "LIQUIDADA" and saga["state"] == "CONCLUIDA"
                and saga["steps"] == saga["completed"] == 6, f"{oid}: nonterminal or incomplete purchase")
        require(cash["status"] == asset["status"] == settlement["status"] == "CONFIRMED"
                and settlement["settlement_id"] == o["settlement_id"]
                and settlement["cash_id"] == cash["reservation_id"]
                and settlement["asset_id_reserved"] == asset["reservation_id"], f"{oid}: reservation or settlement")
        require(o["asset"] == asset["asset"] == settlement["asset"] == raw["asset"]
                and o["cents"] == cash["cents"] == settlement["cents"] == cents
                and o["qty"] == asset["qty"] == settlement["qty"] == 1
                and o["catalog_version"] == settlement["catalog_version"] == "pilot-v1", f"{oid}: purchase snapshot")
        require(abs(saga["received_us"] - o["created_us"]) <= 1 and saga["terminal_us"] >= saga["received_us"],
                f"{oid}: inconsistent Saga timestamps")
    require(data["account"] == [{"available": (units-count)*cents, "reserved": 0, "consumed": count*cents}],
            "cash not conserved")
    positions = {p["owner"]: (p["available"], p["reserved"]) for p in data["positions"]}
    require(positions == {"COUNTERPARTY": (units-count, 0), raw["buyer"]: (count, 0)}, "assets not conserved")


def metrics(raw, plan):
    verify_round(raw, plan)
    start = (raw["generator"]["startMs"] + 1000 * plan["warmupSeconds"]) * 1000
    end = start + plan["windowSeconds"] * 1000000
    sagas = raw["snapshot"]["sagas"]
    cohort = [s for s in sagas if start <= s["received_us"] < end]
    require(len(cohort) > 0, "no order received during the measurement window")
    times = sorted((s["terminal_us"] - s["received_us"]) / 1000 for s in cohort)
    finished = sum(start <= s["terminal_us"] < end for s in sagas)
    resources = {}
    for name in ("cpuFraction", "heapBytes"):
        series = raw["resources"][name]
        require(series.keys() == SERVICES, f"incomplete Prometheus targets for {name}")
        resources[name] = {}
        for svc, samples in series.items():
            values = [float(v) for _, v in samples]
            require(len(values) >= plan["windowSeconds"] // 2 and all(math.isfinite(v) and v >= 0 for v in values),
                    f"missing or invalid samples for {svc}/{name}")
            resources[name][svc] = round(statistics.mean(values), 5)
    return {"rate": raw["rate"], "block": raw["block"], "orders": len(sagas), "measured": len(times),
            "finishedInWindow": finished, "completedRps": round(finished / plan["windowSeconds"], 4),
            "p50Ms": round(percentile(times, .50), 3), "p95Ms": round(percentile(times, .95), 3),
            "p99Ms": round(percentile(times, .99), 3),
            "iqrMs": round(percentile(times, .75) - percentile(times, .25), 3),
            "backlogAtStart": sum(s["received_us"] < start <= s["terminal_us"] for s in sagas),
            "backlogAtEnd": sum(s["received_us"] < end <= s["terminal_us"] for s in sagas),
            "drainSeconds": round(max(0, max(s["terminal_us"] for s in sagas) - end)/1e6, 3),
            "retries": sum(s["retries"] for s in sagas), "resourcesMean": resources}


def variation(values):
    mean = statistics.mean(values)
    return round(statistics.stdev(values) / mean, 5) if mean > 0 else None


def summarize(folder):
    manifest_bytes = (folder / "manifest.json").read_bytes()
    manifest = json.loads(manifest_bytes)
    manifest_hash = hashlib.sha256(manifest_bytes).hexdigest()
    plan = manifest["plan"]
    runs = []
    for spec in manifest["schedule"]:
        path = folder / spec["file"]
        with gzip.open(path, "rt") as file:
            raw = json.load(file)
        require(raw["manifestSha256"] == manifest_hash, f"round belongs to another manifest: {path.name}")
        require((raw["block"], raw["rate"]) == (spec["block"], spec["rate"]), f"unexpected round in {path.name}")
        runs.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(), **metrics(raw, plan)))
    levels = []
    for rate in plan["rates"]:
        group = [r for r in runs if r["rate"] == rate]
        rps = [r["completedRps"] for r in group]
        p95 = [r["p95Ms"] for r in group]
        cv_rps, cv_p95 = variation(rps), variation(p95)
        repetitions = (max(5, math.ceil((1.96 * max(cv_rps, cv_p95) / .1) ** 2))
                       if cv_rps is not None and cv_p95 is not None else None)
        sustained = sum(r >= .9 * rate for r in rps)
        levels.append({"offeredRps": rate, "orders": sum(r["orders"] for r in group),
                       "measured": sum(r["measured"] for r in group),
                       "medianCompletedRps": statistics.median(rps), "medianP95Ms": statistics.median(p95),
                       "medianP99Ms": statistics.median(r["p99Ms"] for r in group),
                       "throughputCV": cv_rps, "p95CV": cv_p95,
                       "roundsAt90Pct": sustained, "sustained": sustained >= math.ceil(.8 * len(group)),
                       "planningRepetitions": repetitions})
    return {"levels": levels, "rounds": runs}


if __name__ == "__main__":
    print(json.dumps(summarize(Path(sys.argv[1])), indent=2, allow_nan=False))
