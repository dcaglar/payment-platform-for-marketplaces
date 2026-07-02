import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

// --- 1. Load Configurations & Credentials ---
const ACCESS_TOKEN = open('../keycloak/output/jwt/payment-service.token').replace(/[\r\n]+$/, '');

// --- 2. Multi-Profile Workload Scenarios ---
const SCENARIOS = {
     single: {
        executor: 'constant-arrival-rate',
        rate: 1,
        timeUnit: '1s',
        duration: '10s',
        preAllocatedVUs: 2,
        maxVUs: 10,
        tags: { test_type: 'single' },
    },
    // A. Smoke Test: Minimal load for validating that scripts and APIs work correctly
    smoke: {
        executor: 'constant-arrival-rate',
        rate: 5,
        timeUnit: '1s',
        duration: '5m',
        preAllocatedVUs: 10,
        maxVUs: 40,
        tags: { test_type: 'smoke' },
    },
    // B. Average Load Test: Simulates expected day-to-day typical user traffic (RPS)
    average: {
        executor: 'ramping-arrival-rate',
        startRate: 0,
        timeUnit: '1s',
        preAllocatedVUs: 50,
        maxVUs: 500,
        stages: [
            { duration: '2m', target: 100 },   // Warm-up to 80 RPS (~60% of single-pod ceiling)
            { duration: '5m', target: 100 },   // Maintain 80 RPS
            { duration: '2m', target: 0 },     // Cool-down
        ],
        tags: { test_type: 'average_load' },
    },
    // C. Stress Test: Push system past average limits to see how resources handle pressure (RPS)
    stress: {
        executor: 'ramping-arrival-rate',
        startRate: 100,
        timeUnit: '1s',
        preAllocatedVUs: 300,
        maxVUs: 8000,
        stages: [
             { duration: '2m', target: 100 },  // Ramp to ~100% of single-pod ceiling
             { duration: '5m', target: 300 },  // Sustained — should trigger HPA
            { duration: '2m', target: 500 },  // Ramp to ~100% of single-pod ceiling
            { duration: '5m', target: 500 },  // Sustained — should trigger HPA
            { duration: '2m', target: 700 },  // Ramp to ~100% of single-pod ceiling
            { duration: '5m', target: 700 },  // Sustained — should trigger HPA
            { duration: '2m', target: 1200 },  // Ramp to ~100% of single-pod ceiling
            { duration: '5m', target: 1200 },  // Sustained — should trigger HPA
            { duration: '2m', target: 1500 },     // Cool-down
            { duration: '5m', target: 1500 },  // Sustained — should trigger HPA
            { duration: '2m', target: 2000 },     // Cool-down
            { duration: '5m', target: 2000 },  // Sustained — should trigger HPA
            { duration: '2m', target: 2500 },     // Cool-down
            { duration: '5m', target: 2500 },  // Sustained — should trigger HPA
            { duration: '2m', target: 1000 },     // Cool-down
           { duration: '5m', target: 100},  // Sustained — should trigger HPA                      { duration: '5m', target: 350 },  // Sustained — should trigger HPA
  { duration: '2m', target: 500 },     // Cool-down
           { duration: '5m', target: 0 },  // Sustained — should trigger HPA
        ],
        tags: { test_type: 'stress' },
    },
    // D. Soak Test: Continuous moderate load over a long time to check memory leaks & slow degradation (RPS)
    soak: {
        executor: 'constant-arrival-rate',
        rate: 80,
        timeUnit: '1s',
        duration: '15m',
        preAllocatedVUs: 50,
        maxVUs: 500,
        tags: { test_type: 'soak' },
    },
    // E. Spike Test: Sudden extreme burst of massive traffic to test caching, buffering, and fast autoscaling (RPS)
    spike: {
        executor: 'ramping-arrival-rate',
        startRate: 0,
        timeUnit: '1s',
        preAllocatedVUs: 50,
        maxVUs: 3000,
        stages: [
            { duration: '1m', target: 5 },    // Normal baseline 5 RPS
            { duration: '10s', target: 250 },  // Sudden spike to 250 RPS (tests 2-pod autoscale)
            { duration: '3m', target: 250 },   // Hold peak 250 RPS
            { duration: '1m', target: 0 },     // Ramp down
        ],
        tags: { test_type: 'spike' },
    },
    // F. Breakpoint Test: Step-wise steady ramp to identify the absolute limits of server failure (RPS)
    breakpoint: {
        executor: 'ramping-arrival-rate',
        startRate: 0,
        timeUnit: '1s',
        preAllocatedVUs: 50,
        maxVUs: 5000,
        stages: [
            { duration: '15m', target: 1500 },  // Ramp to find true 2-pod ceiling
        ],
        tags: { test_type: 'breakpoint' },
    }
};

// Select the profile using an environment variable (defaults to smoke)
// Example: k6 run -e PROFILE=stress k6-payment-flow.js
const PROFILE = __ENV.PROFILE || 'smoke';

export const options = {
    scenarios: {
        [PROFILE]: SCENARIOS[PROFILE] || SCENARIOS.smoke
    },
    thresholds: {
        // SLA Checks
        'http_req_failed': ['rate <= 0.05'],     // Under 5% fail rate
        'http_req_duration': ['p(95) <= 1000'],  // 95% of responses under 1 second
    }
};

// Custom metrics to show up explicitly in the summary
const createBlocked = new Trend('create_1_blocked');
const createConnecting = new Trend('create_2_connecting');
const createTls = new Trend('create_3_tls_handshaking');
const createSending = new Trend('create_4_sending');
const createWaiting = new Trend('create_5_ttfb_backend_processing');
const createReceiving = new Trend('create_6_receiving_body');
const createDuration = new Trend('create_7_total_duration');

const authBlocked = new Trend('auth_1_blocked');
const authConnecting = new Trend('auth_2_connecting');
const authTls = new Trend('auth_3_tls_handshaking');
const authSending = new Trend('auth_4_sending');
const authWaiting = new Trend('auth_5_ttfb_backend_processing');
const authReceiving = new Trend('auth_6_receiving_body');
const authDuration = new Trend('auth_7_total_duration');

// --- 3. Helper Functions ---
function randomId(prefix) {
    return `${prefix}-${Math.floor(Math.random() * 1e8)}`;
}

function generateUuidV7() {
    const hex = () => Math.floor(Math.random() * 16).toString(16);
    const hexN = (n) => {
        let str = '';
        for (let i = 0; i < n; i++) str += hex();
        return str;
    };
    return `${hexN(8)}-${hexN(4)}-7${hexN(3)}-8${hexN(3)}-${hexN(12)}`;
}

// --- Marketplace Registry (mirrors account_directory.csv) ---
// Each marketplace has exactly 10 sellers following the naming convention
// SELLER-{marketplaceIndex}-{sellerIndex} as defined in the account directory.
const MARKETPLACE_REGISTRY = [
    {
        account: 'MARKETPLACE-1',
        sellers: Array.from({ length: 10 }, (_, i) => `SELLER-1-${i + 1}`)
    },
    {
        account: 'MARKETPLACE-2',
        sellers: Array.from({ length: 10 }, (_, i) => `SELLER-2-${i + 1}`)
    },
    {
        account: 'MARKETPLACE-3',
        sellers: Array.from({ length: 10 }, (_, i) => `SELLER-3-${i + 1}`)
    },
    {
        account: 'MARKETPLACE-4',
        sellers: Array.from({ length: 10 }, (_, i) => `SELLER-4-${i + 1}`)
    },
    {
        account: 'MARKETPLACE-5',
        sellers: Array.from({ length: 10 }, (_, i) => `SELLER-5-${i + 1}`)
    },
];

/**
 * Picks a random marketplace from the registry on each call.
 * Returns { account, sellers } so the caller knows which seller pool to use.
 */
function pickRandomMarketplace() {
    return MARKETPLACE_REGISTRY[Math.floor(Math.random() * MARKETPLACE_REGISTRY.length)];
}

/**
 * Returns `count` unique sellers sampled at random from the provided seller pool.
 * Seller pool is always scoped to the chosen marketplace.
 */
function getUniqueSellers(sellerPool, count) {
    const shuffled = sellerPool.slice().sort(() => 0.5 - Math.random());
    return shuffled.slice(0, count);
}

function generateRandomOrder(sellerPool) {
    const totalQuantity = Math.floor(Math.random() * 9000) + 1000;
    const numSellers = Math.floor(Math.random() * 3) + 2;
    const sellers = getUniqueSellers(sellerPool, numSellers);

    const splits = [];
    let remaining = totalQuantity;

    if (Math.random() > 0.5) {
        const commissionPct = (Math.floor(Math.random() * 8) + 2) / 100;
        const commissionAmt = Math.floor(totalQuantity * commissionPct);
        splits.push({ type: "Commission", amount: { quantity: commissionAmt, currency: "EUR" } });
        remaining -= commissionAmt;
    }

    for (let i = 0; i < numSellers; i++) {
        if (i === numSellers - 1) {
            splits.push({ type: "BalanceAccount", account: sellers[i], amount: { quantity: remaining, currency: "EUR" } });
        } else {
            // Ensure we leave at least enough for the remaining sellers (minimum 1 each)
            let maxChunk = remaining - (numSellers - 1 - i);
            let chunk = Math.floor(Math.random() * (maxChunk * 0.6));
            if (chunk < 1) chunk = 1;

            splits.push({ type: "BalanceAccount", account: sellers[i], amount: { quantity: chunk, currency: "EUR" } });
            remaining -= chunk;
        }
    }

    return {
        totalAmount: { quantity: totalQuantity, currency: "EUR" },
        splits: splits
    };
}

// --- 4. Main User Journey ---
export default function () {
    const baseUrl = __ENV.BASE_URL || "http://57.153.89.116";

    // Pick a random marketplace (and its scoped seller pool) for this iteration
    const marketplace = pickRandomMarketplace();

    const headers = {
        'Authorization': `Bearer ${ACCESS_TOKEN}`,
        'Content-Type': 'application/json',
    };

    // --- STEP A: Create a Payment Intent ---
    const createUrl = `${baseUrl}/api/v1/payments`;
    const orderData = generateRandomOrder(marketplace.sellers);

    const createPayload = JSON.stringify({
        orderId: randomId('ORD'),
        buyerId: randomId('BUYER'),
        merchantAccount: marketplace.account,
        processingModel: "MARKETPLACE",
        totalAmount: orderData.totalAmount,
        splits: orderData.splits
    });

    const createParams = {
        headers: Object.assign({ 'Idempotency-Key': generateUuidV7() }, headers),
        tags: { name: 'CreateIntent' }
    };

    const createRes = http.post(createUrl, createPayload, createParams);

    // Detailed metrics for Create
    createBlocked.add(createRes.timings.blocked);
    createConnecting.add(createRes.timings.connecting);
    createTls.add(createRes.timings.tls_handshaking);
    createSending.add(createRes.timings.sending);
    createWaiting.add(createRes.timings.waiting);
    createReceiving.add(createRes.timings.receiving);
    createDuration.add(createRes.timings.duration);

    const isCreated = check(createRes, {
        '1. Payment Intent created successfully (201)': (r) => r.status === 201
    });

    // --- STEP B: Authorize the Payment ---
    if (isCreated) {
        const paymentIntentId = createRes.json().paymentIntentId;

        // Realistic human pacing delay
        sleep(0.2);

        const authUrl = `${baseUrl}/api/v1/payments/${paymentIntentId}/authorize`;
        const authPayload = JSON.stringify({
            paymentMethod: { type: 'CardToken', token: 'tok_visa', cvc: '123' }
        });

        const authParams = {
            headers: Object.assign({ 'Idempotency-Key': generateUuidV7() }, headers),
            tags: { name: 'Authorize' }
        };

        const authRes = http.post(authUrl, authPayload, authParams);

        // Detailed metrics for Auth
        authBlocked.add(authRes.timings.blocked);
        authConnecting.add(authRes.timings.connecting);
        authTls.add(authRes.timings.tls_handshaking);
        authSending.add(authRes.timings.sending);
        authWaiting.add(authRes.timings.waiting);
        authReceiving.add(authRes.timings.receiving);
        authDuration.add(authRes.timings.duration);

        check(authRes, {
            '2. Payment authorized successfully (200/201)': (r) => r.status === 200 || r.status === 201
        });
    }
}