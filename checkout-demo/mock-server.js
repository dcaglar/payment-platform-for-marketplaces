/**
 * Mock backend for the checkout demo.
 *
 * Replaces server.js (the proxy) AND payment-service, so the React app runs with no
 * Keycloak, no payment-service and no Kubernetes. It listens on the same port (3001)
 * and serves the same three endpoints the browser calls.
 *
 * Create payment follows the real server's idempotency rules
 * (IdempotencyService in payment-application):
 *   - new key                         -> 201 (or 202 when the order id contains "TIMEOUT")
 *   - key PENDING, same body          -> 409 + Retry-After: 2
 *   - key COMPLETED, same body        -> 200 replay of the stored response + Idempotent-Replayed: true
 *   - key exists, different body      -> 422
 *   (the body hash is compared before the status, like the real server)
 *
 * Error responses are shaped like the proxy: { error, status, statusText, details },
 * with Retry-After forwarded on 409, and the same CORS exposed headers as server.js.
 *
 * The client secret is simulated (sim_cs_...), like payment-service's SIMULATED gateway. The page
 * then shows Stripe's card form in deferred mode (publishable key only), and "Pay Now" calls authorize.
 *
 * Settings (environment variables):
 *   MOCK_STRICT=true|false     (default true)  validate like the real server: UUIDv7 key,
 *                               merchantAccount, processingModel, and the split rules
 *   MOCK_CREATE_DELAY_MS=<ms>  (default 1500)  how long a create stays PENDING
 *
 * Inspect what was created:  GET http://localhost:3001/__mock/state
 * Clear everything:          POST http://localhost:3001/__mock/reset
 */
import express from 'express';
import cors from 'cors';
import { createHash, randomBytes } from 'crypto';

const PORT = process.env.PORT || 3001;
const STRICT = process.env.MOCK_STRICT !== 'false';
const CREATE_DELAY_MS = parseInt(process.env.MOCK_CREATE_DELAY_MS || '1500');

// idempotency key -> { requestHash, status: 'PENDING' | 'COMPLETED', httpStatus, response }
const idempotencyKeys = new Map();
// paymentIntentId -> payment intent
const paymentIntents = new Map();
// paymentIntentId -> how many times its status was polled (for the TIMEOUT case)
const pollCounts = new Map();

let intentCounter = 0;

const app = express();
app.use(cors({ exposedHeaders: ['Retry-After', 'Location', 'Idempotent-Replayed'] }));
app.use(express.json());

// ---------------------------------------------------------------- helpers

function log(message) {
  console.log(`[mock ${new Date().toISOString().substring(11, 23)}] ${message}`);
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** A UUID's version is the first hex digit of its third group: xxxxxxxx-xxxx-Vxxx-... */
function isUuidV7(value) {
  const pattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  if (!pattern.test(value)) {
    return false;
  }
  return value.charAt(14) === '7';
}

/** Same JSON with object keys sorted, so field order does not change the hash. */
function canonicalJson(value) {
  if (Array.isArray(value)) {
    const items = [];
    for (const item of value) {
      items.push(canonicalJson(item));
    }
    return '[' + items.join(',') + ']';
  }
  if (value !== null && typeof value === 'object') {
    const keys = Object.keys(value).sort();
    const parts = [];
    for (const key of keys) {
      // fields starting with "_" (e.g. _idempotencyKey) are not part of the API body;
      // the real server ignores unknown fields, so they are left out of the hash too
      if (key.startsWith('_')) {
        continue;
      }
      parts.push(JSON.stringify(key) + ':' + canonicalJson(value[key]));
    }
    return '{' + parts.join(',') + '}';
  }
  return JSON.stringify(value);
}

function hashBody(body) {
  return createHash('sha256').update(canonicalJson(body)).digest('hex');
}

/** Same rules as PaymentValidator in payment-service; returns a message, or null when fine. */
function checkSplits(body) {
  let splits = body.splits;
  if (!splits) {
    splits = [];
  }
  if (body.processingModel === 'DIRECT_MERCHANT' && splits.length > 0) {
    return 'Invalid processing model: DIRECT_MERCHANT must have no splits, MARKETPLACE must have splits';
  }
  if (body.processingModel === 'MARKETPLACE') {
    if (splits.length === 0) {
      return 'Invalid processing model: DIRECT_MERCHANT must have no splits, MARKETPLACE must have splits';
    }
    let sum = 0;
    for (const split of splits) {
      sum += split.amount.quantity;
    }
    if (sum !== body.totalAmount.quantity) {
      return `Sum of splits (${sum}) must equal totalAmount (${body.totalAmount.quantity})`;
    }
  }
  return null;
}

function newPaymentIntentId() {
  intentCounter++;
  return 'pi_mock' + String(intentCounter).padStart(4, '0');
}

/** Error shaped like the proxy: payment-service status, wrapped body, Retry-After forwarded. */
function sendError(res, status, statusText, message, retryAfterSeconds) {
  if (retryAfterSeconds) {
    res.set('retry-after', String(retryAfterSeconds));
  }
  res.status(status).json({
    error: 'Payment service request failed',
    status: status,
    statusText: statusText,
    details: { status: status, message: message }
  });
}

// ---------------------------------------------------------------- create payment

app.post('/api/checkout/process-payment', async (req, res) => {
  const body = req.body || {};
  const key = req.headers['idempotency-key'];
  log(`POST process-payment key=${key} orderId=${body.orderId}`);

  // proxy checks
  if (!body.orderId || !body.buyerId) {
    return res.status(400).json({ error: 'Invalid payment data', message: 'Missing required fields: orderId, buyerId' });
  }
  if (!key) {
    return res.status(400).json({ error: 'Idempotency-Key header is required' });
  }

  // payment-service request validation
  if (STRICT) {
    if (!isUuidV7(key)) {
      log(`  -> 400: key is not a UUIDv7`);
      return sendError(res, 400, 'Bad Request', 'Idempotency-Key: Must be a valid UUIDv7 format');
    }
    if (!body.merchantAccount || !body.processingModel) {
      log(`  -> 400: merchantAccount/processingModel missing`);
      return sendError(res, 400, 'Bad Request', 'merchantAccount and processingModel are required');
    }
    const splitsProblem = checkSplits(body);
    if (splitsProblem) {
      log(`  -> 400: ${splitsProblem}`);
      return sendError(res, 400, 'Bad Request', splitsProblem);
    }
  }

  const requestHash = hashBody(body);
  const existing = idempotencyKeys.get(key);

  // --- key already known
  if (existing) {
    if (existing.requestHash !== requestHash) {
      log(`  -> 422: same key, different body`);
      return sendError(res, 422, 'Unprocessable Entity', 'Request body mismatch for existing key');
    }
    if (existing.status === 'PENDING') {
      log(`  -> 409: original request still processing`);
      return sendError(res, 409, 'Conflict', 'Original request still processing. Please wait.', 2);
    }
    log(`  -> 200: replay of stored response (${existing.response.paymentIntentId})`);
    res.set('idempotent-replayed', 'true');
    res.set('location', `/api/v1/payments/${existing.response.paymentIntentId}`);
    return res.status(200).json({ payment: existing.response });
  }

  // --- first request: lock the key, create the intent, store the response
  idempotencyKeys.set(key, { requestHash: requestHash, status: 'PENDING', httpStatus: null, response: null });
  log(`  key PENDING, processing for ${CREATE_DELAY_MS} ms`);
  await sleep(CREATE_DELAY_MS);

  const paymentIntentId = newPaymentIntentId();
  const pspTimedOut = String(body.orderId).toUpperCase().includes('TIMEOUT');

  const intent = {
    paymentIntentId: paymentIntentId,
    clientSecret: pspTimedOut ? null : `sim_cs_mock_${paymentIntentId}`,
    status: pspTimedOut ? 'CREATED_PENDING' : 'CREATED',
    buyerId: body.buyerId,
    orderId: body.orderId,
    totalAmount: body.totalAmount,
    createdAt: new Date().toISOString()
  };
  paymentIntents.set(paymentIntentId, intent);

  // the stored response is a copy: like the real server, it is never updated afterwards
  const storedResponse = { ...intent };
  const httpStatus = pspTimedOut ? 202 : 201;
  idempotencyKeys.set(key, { requestHash: requestHash, status: 'COMPLETED', httpStatus: httpStatus, response: storedResponse });

  log(`  -> ${httpStatus}: created ${paymentIntentId} (${intent.status})`);
  res.set('location', `/api/v1/payments/${paymentIntentId}`);
  if (pspTimedOut) {
    res.set('retry-after', '2');
  }
  res.status(httpStatus).json({ payment: storedResponse });
});

// ---------------------------------------------------------------- polling after 202

app.get('/api/checkout/payment-status/:paymentId', (req, res) => {
  const id = req.params.paymentId;
  const intent = paymentIntents.get(id);
  if (!intent) {
    return res.status(404).json({ error: 'Payment intent not found', status: 404 });
  }

  // the background PSP call "finishes" on the third poll
  let polls = pollCounts.get(id) || 0;
  polls++;
  pollCounts.set(id, polls);
  if (intent.status === 'CREATED_PENDING' && polls >= 3) {
    intent.status = 'CREATED';
    intent.clientSecret = `sim_cs_mock_${id}`;
  }

  log(`GET payment-status ${id} poll=${polls} -> ${intent.status}`);
  res.status(200).json({ payment: { ...intent } });
});

// ---------------------------------------------------------------- authorize

app.post('/api/checkout/authorize-payment/:paymentId', (req, res) => {
  const id = req.params.paymentId;
  const intent = paymentIntents.get(id);
  if (!intent) {
    return res.status(404).json({ error: 'Payment intent not found', status: 404 });
  }
  intent.status = 'AUTHORIZED';
  log(`POST authorize-payment ${id} -> AUTHORIZED`);
  res.status(200).json({ payment: { ...intent } });
});

// ---------------------------------------------------------------- inspection

app.get('/__mock/state', (req, res) => {
  const keys = [];
  for (const [key, record] of idempotencyKeys) {
    let paymentIntentId = null;
    if (record.response) {
      paymentIntentId = record.response.paymentIntentId;
    }
    keys.push({ key: key, status: record.status, paymentIntentId: paymentIntentId });
  }
  const intents = [];
  for (const intent of paymentIntents.values()) {
    intents.push({ paymentIntentId: intent.paymentIntentId, orderId: intent.orderId, status: intent.status });
  }
  res.json({ idempotencyKeys: keys, paymentIntents: intents });
});

app.post('/__mock/reset', (req, res) => {
  idempotencyKeys.clear();
  paymentIntents.clear();
  pollCounts.clear();
  intentCounter = 0;
  log('state reset');
  res.json({ reset: true });
});

app.listen(PORT, () => {
  console.log(`Mock backend on http://localhost:${PORT}`);
  console.log(`  strict validation (UUIDv7 key, merchantAccount, processingModel): ${STRICT}`);
  console.log(`  create stays PENDING for: ${CREATE_DELAY_MS} ms`);
  console.log(`  state: GET http://localhost:${PORT}/__mock/state`);
});
