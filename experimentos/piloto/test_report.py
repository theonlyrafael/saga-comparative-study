import copy
import unittest

from report import SERVICES, metrics, percentile
from run import schedule


class PilotReportTest(unittest.TestCase):
    def setUp(self):
        self.plan = dict(seed=17, unitsPerRound=10, unitPriceCents=2550, rates=[1, 2],
                         repetitions=5, warmupSeconds=2, windowSeconds=4, drainSeconds=20)
        data = {name: [] for name in ("orders", "cash", "assets", "settlements", "sagas")}
        for i, (received, terminal) in enumerate([(0, 1), (1, 2), (2, 5), (3, 6), (4, 7), (5, 5.9)]):
            common = dict(order_id=f"order-{i}", saga_id=f"saga-{i}")
            snapshot = dict(asset="A", qty=1, cents=2550, catalog_version="pilot-v1")
            data["orders"].append(dict(common, **snapshot, key=f"K{i}", settlement_id=i,
                                       created_us=received*1000000, status="LIQUIDADA"))
            data["sagas"].append(dict(common, state="CONCLUIDA", received_us=received*1000000,
                                      terminal_us=round(terminal*1000000), steps=6, completed=6, retries=0))
            data["cash"].append(dict(common, status="CONFIRMED", reservation_id=f"C{i}", cents=2550))
            data["assets"].append(dict(common, status="CONFIRMED", reservation_id=f"A{i}", asset="A", qty=1))
            data["settlements"].append(dict(common, **snapshot, status="CONFIRMED", settlement_id=i,
                                            cash_id=f"C{i}", asset_id_reserved=f"A{i}"))
        data["account"] = [dict(available=10200, reserved=0, consumed=15300)]
        data["positions"] = [dict(owner="COUNTERPARTY", available=4, reserved=0), dict(owner="B", available=6, reserved=0)]
        resources = {m: {s: [[2, "0.2"], [4, "0.3"]] for s in SERVICES} for m in ("cpuFraction", "heapBytes")}
        generator = dict(startMs=0, requests=6, iterations=6, receipts=6, dropped=0)
        self.raw = dict(snapshot=data, buyer="B", asset="A", generator=generator, rate=1, block=1,
                        drained=True, k6Exit=0, resources=resources)

    def test_steady_completions_and_arrival_cohort_use_separate_half_open_windows(self):
        result = metrics(self.raw, self.plan)
        self.assertEqual(4, result["measured"])
        self.assertEqual(3, result["finishedInWindow"])
        self.assertEqual(.75, result["completedRps"])
        self.assertEqual(3000, result["p95Ms"])
        self.assertEqual((1, 2, 1), (result["backlogAtStart"], result["backlogAtEnd"], result["drainSeconds"]))
        self.assertAlmostEqual(8.85, percentile([1, 2, 8, 9], .95))

    def test_wrong_reservation_fails_even_if_balances_match(self):
        self.raw["snapshot"]["settlements"][0]["cash_id"] = "C1"
        with self.assertRaisesRegex(ValueError, "reservation or settlement"):
            metrics(self.raw, self.plan)

    def test_duplicate_and_missing_effects_cannot_cancel_out(self):
        self.raw["snapshot"]["assets"][1] = copy.copy(self.raw["snapshot"]["assets"][0])
        with self.assertRaisesRegex(ValueError, "duplicate effect"):
            metrics(self.raw, self.plan)

    def test_dropped_arrival_and_missing_scrape_invalidate_the_round(self):
        self.raw["generator"]["dropped"] = 1
        with self.assertRaisesRegex(ValueError, "generator"):
            metrics(self.raw, self.plan)
        self.raw["generator"]["dropped"] = 0
        del self.raw["resources"]["heapBytes"]["account"]
        with self.assertRaisesRegex(ValueError, "Prometheus"):
            metrics(self.raw, self.plan)

    def test_first_block_ramps_and_each_seeded_block_repeats_all_levels(self):
        plan = dict(self.plan, unitsPerRound=100)
        rounds = schedule(plan)
        self.assertEqual(rounds, schedule(plan))
        self.assertEqual([1, 2], [r["rate"] for r in rounds[:2]])
        for block in range(1, 6):
            self.assertCountEqual([1, 2], [r["rate"] for r in rounds if r["block"] == block])


if __name__ == "__main__":
    unittest.main()
