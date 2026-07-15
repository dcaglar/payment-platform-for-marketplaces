import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

// --- 1. Load Configurations & Credentials ---
const ACCESS_TOKEN = open('../keycloak/output/jwt/payment-service.token').replace(/[\r\n]+$/, '');
const BASE_URL = open('./endpoint.txt').replace(/[\r\n]+$/, '');
const CREATE_PAYMENT_INTENT_ENDPOINT = `${BASE_URL}/api/v1/payments`;
const AUTHORIZE_ENDPOINT = `${BASE_URL}/api/v1/payments`;

// --- 2. Multi-Profile Workload Scenarios ---
const SCENARIOS = {
     single: {
        executor: 'constant-arrival-rate',
        rate: 1,
        timeUnit: '1s',
        duration: '30s',
        preAllocatedVUs: 2,
        maxVUs: 20,
        tags: { test_type: 'single' },
    },
    // A. Smoke Test: Minimal load for validating that scripts and APIs work correctly
    smoke: {
        executor: 'constant-arrival-rate',
        rate: 15,
        timeUnit: '1s',
        duration: '5m',
        preAllocatedVUs: 100,
        maxVUs: 500,
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
            { duration: '2m', target: 80 },   // Warm-up to 80 RPS (~60% of single-pod ceiling)
            { duration: '20m', target: 80 },   // Maintain 80 RPS
            { duration: '2m', target: 0 },     // Cool-down
        ],
        tags: { test_type: 'average_load' },
    },
    // C. Stress Test: Push system past average limits to see how resources handle pressure (RPS)
    stress: {
        executor: 'ramping-arrival-rate',
        startRate: 2,
        timeUnit: '1s',
        preAllocatedVUs: 100,
        maxVUs: 1500,
        stages: [
            { duration: '2m', target: 5 },  // Ramp to ~100% of single-pod ceiling
            { duration: '5m', target: 10 },  // Sustained — should trigger HPA
            { duration: '2m', target: 20 },     // Cool-down
            { duration: '5m', target: 30 },     // Cool-down
             { duration: '2m', target: 40 },     // Cool-down
              { duration: '5m', target: 50 },     // Cool-down
                { duration: '2m', target: 100 },     // Cool-down
                 { duration: '5m', target: 130 },     // Cool-down
                 { duration: '2m', target: 150 },     // Cool-down
                { duration: '5m', target: 0 }   // Cool-down
        ],
        tags: { test_type: 'stress' },
    },
    // D. Soak Test: Continuous moderate load over a long time to check memory leaks & slow degradation (RPS)
    soak: {
        executor: 'constant-arrival-rate',
        rate: 80,
        timeUnit: '1s',
        duration: '30m',
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
            { duration: '15m', target: 350 },  // Ramp to find true 2-pod ceiling
        ],
        tags: { test_type: 'breakpoint' },
    },
    // G. Baseline Test: Establish low-concurrency baseline to prove latency remains flat (VUs)
    baseline: {
        executor: 'ramping-arrival-rate',
        startRate: 5,
        timeUnit: '1s',
       preAllocatedVUs: 250,
       maxVUs: 500,
        stages: [

           { duration: '2m', target: 100 },
            { duration: '15m', target: 100 },
            { duration: '2m', target: 150 },
             { duration: '15m', target: 150 },
                  { duration: '2m', target: 20 },

        ],
        tags: { test_type: 'baseline' },
    }
};

// Select the profile using an environment variable (defaults to smoke)
// Example: k6 run -e PROFILE=stress k6-payment-flow.js
const PROFILE = __ENV.PROFILE || 'smoke';
const API_BASE_URL = __ENV.API_BASE_URL || 'http://localhost';

export const options = {
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(99)'],
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
const createDuration = new Trend('create_duration');
const authDuration = new Trend('auth_duration');

// --- 3. Helper Functions ---
function randomId(prefix) {
    return `${prefix}-${Math.floor(Math.random() * 1e8)}`;
}

function generateUuidV7() {
    // 1. 48-bit Timestamp (Unix Epoch in milliseconds)
    const timestampMs = Date.now();
    let tsHex = timestampMs.toString(16).padStart(12, '0');

    // 2. 74 bits of randomness
    const hex = () => Math.floor(Math.random() * 16).toString(16);
    const hexN = (n) => {
        let str = '';
        for (let i = 0; i < n; i++) str += hex();
        return str;
    };

    const variantHex = ['8', '9', 'a', 'b'][Math.floor(Math.random() * 4)];
    return `${tsHex.substring(0, 8)}-${tsHex.substring(8, 12)}-7${hexN(3)}-${variantHex}${hexN(3)}-${hexN(12)}`;
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
    // Pick a random marketplace (and its scoped seller pool) for this iteration
    const marketplace = pickRandomMarketplace();

    const headers = {
        'Authorization': `Bearer ${ACCESS_TOKEN}`,
        'Content-Type': 'application/json',
    };

    // --- STEP A: Create a Payment Intent ---
    const createUrl = CREATE_PAYMENT_INTENT_ENDPOINT;
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
    
    // Metric for Create
    createDuration.add(createRes.timings.duration);

    const isCreated = check(createRes, {
        '1. Payment Intent created successfully (201)': (r) => r.status === 201
    });

    // --- STEP B: Authorize the Payment ---
    if (isCreated) {
        const paymentIntentId = createRes.json().paymentIntentId;

        // Realistic human pacing delay
        sleep(0.2);

        const authUrl = `${AUTHORIZE_ENDPOINT}/${paymentIntentId}/authorize`;
        const authPayload = JSON.stringify({
            paymentMethod: { type: 'CardToken', token: 'tok_visa', cvc: '123' }
        });

        const authParams = {
            headers: Object.assign({ 'Idempotency-Key': generateUuidV7() }, headers),
            tags: { name: 'Authorize' }
        };

        const authRes = http.post(authUrl, authPayload, authParams);
        
        // Metric for Auth
        authDuration.add(authRes.timings.duration);

        check(authRes, {
            '2. Payment authorized successfully (200/201)': (r) => r.status === 200 || r.status === 201
        });
    }
}