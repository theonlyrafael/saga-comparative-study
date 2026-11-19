import copy
import json
import unittest
from datetime import datetime
from pathlib import Path
from uuid import uuid4

from jsonschema import Draft202012Validator, FormatChecker

SCHEMA = json.loads((Path(__file__).resolve().parents[1] / "v1/envelope.schema.json").read_text())
CHECKER = FormatChecker()


@CHECKER.checks("date-time", raises=ValueError)
def timestamp(value):
    if not isinstance(value, str):
        return True
    return "T" in value and datetime.fromisoformat(value.replace("Z", "+00:00")).tzinfo is not None


VALIDATOR = Draft202012Validator(SCHEMA, format_checker=CHECKER)


def order_received():
    return {
        "type": "OrdemRecebida", "schemaVersion": 1,
        **{field: str(uuid4()) for field in ("messageId", "orderId", "sagaId", "operationId")},
        "occurredAt": "2026-11-18T12:00:00Z",
        "payload": {"clienteId": "CLI001", "ativoId": "ATV002", "quantidade": 3,
                    "precoUnitario": "25.50", "valorTotal": "76.50", "versaoCatalogo": "demo-v1"}
    }


class EnvelopeTest(unittest.TestCase):
    def test_order_received(self):
        Draft202012Validator.check_schema(SCHEMA)
        VALIDATOR.validate(order_received())

    def test_invalid_envelopes(self):
        for key, value in (("schemaVersion", 2), ("type", "unknown"), ("messageId", "x"),
                           ("occurredAt", "today"), ("unexpected", True)):
            with self.subTest(key=key):
                self.assertFalse(VALIDATOR.is_valid(order_received() | {key: value}))

    def test_captured_values(self):
        for key, value in (("valorTotal", 76.5), ("precoUnitario", "0.00"),
                           ("quantidade", 1.5), ("ativoId", ""), ("unexpected", True)):
            message = order_received()
            message["payload"][key] = value
            with self.subTest(key=key):
                self.assertFalse(VALIDATOR.is_valid(message))

    def test_settlement_requires_both_reservations(self):
        message = order_received() | {"type": "ConfirmarLiquidacao"}
        message["payload"].update(reservaSaldoId=str(uuid4()), reservaAtivoId=str(uuid4()))
        VALIDATOR.validate(message)
        for key in ("reservaSaldoId", "reservaAtivoId"):
            incomplete = copy.deepcopy(message)
            del incomplete["payload"][key]
            self.assertFalse(VALIDATOR.is_valid(incomplete))

    def test_terminal_command(self):
        for state, detail in (("REJEITADA", {"motivo": "SALDO_INSUFICIENTE"}),
                              ("LIQUIDADA", {"liquidacaoId": str(uuid4())})):
            message = order_received() | {"type": "FinalizarOrdem"}
            message["payload"]["estado"] = state
            self.assertFalse(VALIDATOR.is_valid(message))
            message["payload"].update(detail)
            VALIDATOR.validate(message)
