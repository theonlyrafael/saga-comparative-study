"""Execute the orchestrated calibration on the private stack prepared by run-load-pilot.sh."""
import datetime
import gzip
import hashlib
import json
import os
import platform
import random
import subprocess
import sys
import uuid
from pathlib import Path

from collect import Stack, resource_samples
from report import metrics, require, summarize

HERE = Path(__file__).resolve().parent
K6 = "grafana/k6:1.3.0@sha256:3ddc8b1a33a2c3d8edc6e99b6a762ae36cba08788463458f5e6a7703e14eb77d"


def schedule(plan):
    rates = plan["rates"]
    require(rates and all(type(r) is int and r > 0 for r in rates) and rates == sorted(set(rates)),
            "rates must be positive, distinct and increasing")
    require(all(type(plan[k]) is int and plan[k] > 0 for k in
                ("repetitions", "warmupSeconds", "windowSeconds", "drainSeconds", "unitsPerRound", "unitPriceCents"))
            and plan["repetitions"] >= 2 and plan["windowSeconds"] >= 4, "invalid plan for variance or telemetry")
    require(max(rates) * (plan["warmupSeconds"] + plan["windowSeconds"]) + 1 <= plan["unitsPerRound"],
            "seed would exhaust the account or inventory")
    rng = random.Random(plan["seed"])
    result = []
    for block in range(1, plan["repetitions"] + 1):
        levels = rates.copy()
        if block > 1:
            rng.shuffle(levels)
        for rate in levels:
            result.append({"block": block, "rate": rate, "file": f"round-{len(result)+1:02}.json.gz"})
    return result


def command(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT, timeout=60).strip()


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def environment(work):
    def limit(name):
        path = Path("/sys/fs/cgroup") / name
        return path.read_text().strip() if path.exists() else None

    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin/java") if java_home else "java"
    jars = sorted(Path("orquestrada").glob("*-service/target/*-SNAPSHOT.jar"))
    require(len(jars) == 5, "build the five service JARs before running the pilot")
    controls = ("OUTBOX_POLL_INTERVAL", "SAGA_TIMEOUT_STEP", "RECONCILIATION_POLL_INTERVAL",
                "SAGA_RETRY_MAX_ATTEMPTS", "SAGA_RETRY_INITIAL_DELAY", "SAGA_RETRY_MAX_DELAY",
                "DB_POOL_MAX_SIZE", "DB_POOL_MIN_IDLE", "RABBITMQ_PREFETCH", "SAGA_TRACE_SAMPLING", "SAGA_TRACE_EXPORT")
    return {"revision": command("git", "rev-parse", "HEAD"), "utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "platform": platform.platform(), "cpuModel": next((line.split(":", 1)[1].strip() for line in
                Path("/proc/cpuinfo").read_text().splitlines() if line.startswith("model name")), platform.machine()),
            "affinityCpus": len(os.sched_getaffinity(0)), "cpuQuotaPeriod": limit("cpu.max"),
            "memoryLimitBytes": limit("memory.max"), "hostMemoryBytes": os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES"),
            "java": command(java, "-version"), "javaOptions": os.environ["JAVA_TOOL_OPTIONS"],
            "docker": command("docker", "version", "--format", "{{.Server.Version}}"),
            "compose": command("docker", "compose", "version", "--short"), "images": (work / "images.txt").read_text().splitlines(),
            "controls": {name: os.environ[name] for name in controls},
            "k6Image": K6, "jarSha256": {p.name: digest(p) for p in jars}}


def generate(api, buyer, asset, rate, duration):
    container = f"saga-k6-{uuid.uuid4().hex}"
    args = ["docker", "run", "--rm", "-i", "--name", container, "--network", "host", K6, "run", "--quiet", "--no-usage-report",
            "--address", "127.0.0.1:0", "--log-output", "stderr",
            "-e", f"API={api}", "-e", f"BUYER={buyer}", "-e", f"ASSET={asset}",
            "-e", f"RATE={rate}", "-e", f"DURATION={duration}s", "-"]
    try:
        with (HERE / "orders.js").open("rb") as script:
            completed = subprocess.run(args, stdin=script, stdout=subprocess.PIPE, text=True, timeout=duration + 60)
    except (KeyboardInterrupt, subprocess.TimeoutExpired):
        subprocess.run(["docker", "rm", "-f", container], capture_output=True, timeout=15)
        raise
    return completed.returncode, json.loads(completed.stdout)


def main(output, work, api, prom, plan_path):
    plan = json.loads(plan_path.read_text())
    rounds = schedule(plan)
    stack = Stack(work / "containers.tsv")
    require(len(set(stack.containers.values())) == 6, "expected five independent databases and a broker")
    command("docker", "run", "--rm", K6, "version")  # Pull before opening a measurement window.
    manifest = {"seedVersion": "pilot-v1", "environment": environment(work), "plan": plan, "schedule": rounds}
    with (output / "manifest.json").open("x") as file:
        json.dump(manifest, file, indent=2)
        file.write("\n")
    manifest_hash = digest(output / "manifest.json")
    for idx, spec in enumerate(rounds, 1):
        buyer, asset = f"BUYER-{idx:02}", f"ASSET-{idx:02}"
        rate = spec["rate"]
        print(f"Rodada {idx}/{len(rounds)} · bloco {spec['block']} · {rate} ordens/s", flush=True)
        stack.seed(buyer, asset, plan)
        code, generator = generate(api, buyer, asset, rate, plan["warmupSeconds"] + plan["windowSeconds"])
        drained = stack.drain(buyer, generator["receipts"], plan["drainSeconds"])
        start = generator["startMs"] / 1000 + plan["warmupSeconds"]
        raw = dict(spec, buyer=buyer, asset=asset, manifestSha256=manifest_hash,
                   generator=generator, k6Exit=code, drained=drained,
                   snapshot=stack.snapshot(buyer, asset), resources=resource_samples(prom, start, start + plan["windowSeconds"]))
        with gzip.open(output / spec["file"], "xt", encoding="utf-8") as file:
            json.dump(raw, file, separators=(",", ":"), allow_nan=False)
        result = metrics(raw, plan)
        print(f"  {result['orders']} liquidadas · {result['completedRps']} concluídas/s · p95 {result['p95Ms']} ms", flush=True)
    with (output / "summary.json").open("x") as file:
        json.dump(summarize(output), file, indent=2, allow_nan=False)
        file.write("\n")


if __name__ == "__main__":
    try:
        main(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3], sys.argv[4], Path(sys.argv[5]))
    except subprocess.CalledProcessError as exc:
        if exc.stderr:
            print(exc.stderr, file=sys.stderr)
        raise
