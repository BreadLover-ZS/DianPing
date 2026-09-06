import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';

const baseUrls = (__ENV.BASE_URLS || __ENV.BASE_URL || 'http://127.0.0.1:8080')
  .split(',')
  .map((value) => value.trim())
  .filter(Boolean);
const tokenFile = __ENV.TOKEN_FILE;
const tokens = new SharedArray('pressure tokens', () => JSON.parse(open(tokenFile)));
const rate = Number(__ENV.RATE || 1000);
const duration = __ENV.DURATION || '15s';
const preAllocatedVUs = Number(__ENV.PREALLOCATED_VUS || Math.max(800, Math.ceil(rate / 2)));
const maxVUs = Number(__ENV.MAX_VUS || Math.max(preAllocatedVUs, rate));
const voucherId = __ENV.VOUCHER_ID;
const tokenOffset = Number(__ENV.TOKEN_OFFSET || 0);
const gracefulStop = __ENV.GRACEFUL_STOP || '10s';
const requireAllAccepted = (__ENV.REQUIRE_ALL_ACCEPTED || 'true') === 'true';

if (!tokenFile || tokens.length === 0) {
  throw new Error('TOKEN_FILE must contain at least one token');
}
if (!Number.isInteger(tokenOffset) || tokenOffset < 0) {
  throw new Error(`TOKEN_OFFSET must be a non-negative integer: ${tokenOffset}`);
}

export const businessAccepted = new Rate('business_accepted');

export const options = {
  scenarios: {
    seckill_arrival: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs,
      maxVUs,
      gracefulStop,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.001'],
    checks: [requireAllAccepted ? 'rate==1' : 'rate>0.999'],
    business_accepted: [requireAllAccepted ? 'rate==1' : 'rate>0.999'],
    dropped_iterations: ['count==0'],
  },
};

export default function () {
  const iteration = exec.scenario.iterationInTest;
  const tokenIndex = tokenOffset + iteration;
  if (tokenIndex >= tokens.length) {
    throw new Error(
      `token budget exhausted: index=${tokenIndex}, size=${tokens.length}`
    );
  }
  const token = tokens[tokenIndex];
  const baseUrl = baseUrls[iteration % baseUrls.length];
  const response = http.post(`${baseUrl}/voucher-order/seckill/${voucherId}`, null, {
    headers: {
      authorization: token,
      'content-type': 'application/json',
    },
    tags: { endpoint: 'seckill-entry', voucher: String(voucherId) },
  });

  let accepted = false;
  try {
    const body = response.json();
    accepted = response.status === 200 && body && body.success === true;
  } catch (_) {
    accepted = false;
  }
  businessAccepted.add(accepted);
  check(response, {
    'HTTP 200': (value) => value.status === 200,
    'business accepted': () => accepted,
  });
}
