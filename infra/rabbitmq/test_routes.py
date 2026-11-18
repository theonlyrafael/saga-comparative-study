"""Exercise RabbitMQ management publish/get on a fresh, isolated vhost."""
import json
import os
import time
import unittest
from pathlib import Path
from urllib.parse import quote
from uuid import uuid4

from configure import configure, request

COMMANDS = {
    "ReservarSaldo": "account", "ConfirmarReservaSaldo": "account", "DesfazerReservaSaldo": "account",
    "ReservarAtivo": "custody", "ConfirmarReservaAtivo": "custody", "DesfazerReservaAtivo": "custody",
    "ConfirmarLiquidacao": "settlement", "FinalizarOrdem": "order"
}


class RoutingTest(unittest.TestCase):
    def test_all_routes(self):
        vhost = "saga-test-" + uuid4().hex
        request("PUT", f"vhosts/{vhost}", {})
        self.addCleanup(request, "DELETE", f"vhosts/{vhost}")
        user = quote(os.environ["RABBITMQ_USER"], safe="")
        request("PUT", f"permissions/{vhost}/{user}", dict(configure=".*", write=".*", read=".*"))
        configure(vhost)
        configure(vhost)
        schema = Path(__file__).resolve().parents[2] / "compartilhado/mensageria/v1/envelope.schema.json"
        types = json.loads(schema.read_text())["properties"]["type"]["enum"]
        for kind in types:
            with self.subTest(kind=kind):
                service = COMMANDS.get(kind, "orchestrator")
                exchange = "commands" if kind in COMMANDS else "responses"
                path = f"exchanges/{vhost}/saga.v1.{exchange}/publish"
                result = request("POST", path, dict(properties={"delivery_mode": 2}, routing_key=kind,
                                                     payload=kind, payload_encoding="string"))
                self.assertTrue(result["routed"])
                queue = f"queues/{vhost}/saga.v1.{service}"
                self.assertTrue(request("GET", queue)["durable"])
                reject = kind == "OrdemRecebida"
                self.receive(queue, kind, "reject_requeue_false" if reject else "ack_requeue_false")
                if reject:
                    self.receive(queue + ".dlq", kind, "ack_requeue_false")
        self.assertFalse(request("POST", path, dict(properties={}, routing_key="Desconhecida",
                                                    payload="", payload_encoding="string"))["routed"])

    def receive(self, queue, expected, mode):
        for _ in range(30):
            result = request("POST", queue + "/get", dict(count=1, ackmode=mode, encoding="auto"))
            if result:
                self.assertEqual(result[0]["payload"], expected)
                return
            time.sleep(0.1)
        self.fail(f"{expected} did not arrive on {queue}")


if __name__ == "__main__":
    unittest.main()
