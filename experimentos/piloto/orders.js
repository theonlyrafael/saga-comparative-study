import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Gauge } from 'k6/metrics';

const rate = Number(__ENV.RATE);
const receipts = new Counter('valid_receipts');
const origin = new Gauge('scenario_start_ms');
const body = JSON.stringify({ clienteId: __ENV.BUYER, ativoId: __ENV.ASSET, quantidade: 1 });

export const options = {
  scenarios: {
    purchase: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration: __ENV.DURATION,
      preAllocatedVUs: Math.max(4, 2 * rate),
      gracefulStop: '15s',
    },
  },
  maxRedirects: 0,
};

export default function () {
  origin.add(exec.scenario.startTime);
  const key = __ENV.BUYER + '-' + exec.scenario.iterationInTest;
  const response = http.post(__ENV.API, body, {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
    timeout: '10s',
  });
  let valid = false;
  if (response.status === 202) {
    try {
      const order = response.json();
      valid = order.estado === 'RECEBIDA' && typeof order.orderId === 'string' &&
        typeof order.sagaId === 'string' && order.clienteId === __ENV.BUYER && order.ativoId === __ENV.ASSET;
    } catch (error) {
      console.error('Invalid order receipt for ' + key);
    }
  }
  receipts.add(valid ? 1 : 0);
}

export function handleSummary(data) {
  const metrics = data.metrics;
  const count = (name) => metrics[name] ? metrics[name].values.count : 0;
  const sample = {
    startMs: metrics.scenario_start_ms.values.value,
    requests: count('http_reqs'),
    iterations: count('iterations'),
    receipts: count('valid_receipts'),
    dropped: count('dropped_iterations'),
    postP95Ms: metrics.http_req_duration.values['p(95)'],
  };
  return { stdout: JSON.stringify(sample) + '\n' };
}
