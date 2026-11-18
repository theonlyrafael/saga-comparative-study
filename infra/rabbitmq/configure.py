"""Import durable topology into the local Compose broker's configured vhost."""
import base64
import json
import os
from pathlib import Path
from urllib.parse import quote
from urllib.request import ProxyHandler, Request, build_opener

TOPOLOGY = Path(__file__).with_name("topology-v1.json")


def request(method, path, body=None):
    auth = f"{os.environ['RABBITMQ_USER']}:{os.environ['RABBITMQ_PASSWORD']}"
    url = f"http://127.0.0.1:{os.environ.get('RABBITMQ_MANAGEMENT_PORT', '15672')}/api/{path}"
    headers = {"Authorization": "Basic " + base64.b64encode(auth.encode()).decode(),
               "Content-Type": "application/json"}
    data = None if body is None else json.dumps(body).encode()
    req = Request(url, data=data, headers=headers, method=method)
    with build_opener(ProxyHandler({})).open(req, timeout=15) as response:
        result = response.read()
    return json.loads(result) if result else None


def configure(vhost):
    request("POST", "definitions/" + quote(vhost, safe=""), json.loads(TOPOLOGY.read_text()))


if __name__ == "__main__":
    configure(os.environ.get("RABBITMQ_VHOST", "saga"))
