import http from 'k6/http';
import { check, sleep } from 'k6';

// 1. INIT STAGE
// MARKETPLACE-1's backend token (keycloak/get-access-token.sh merchant-api MARKETPLACE-1)
const ACCESS_TOKEN = open('../keycloak/output/jwt/MARKETPLACE-1.token').replace(/[\r\n]+$/, '');

export const options = {
    vus: 1,
    iterations: 1,
};

const MARKETPLACE = "MARKETPLACE-1";
const SELLERS = Array.from({length: 10}, (_, i) => `SELLER-1-${i+1}`);

// Helper: Shuffles an array and returns the first N elements to ensure uniqueness
function getUniqueSellers(count) {
    const shuffled = SELLERS.slice().sort(() => 0.5 - Math.random());
    return shuffled.slice(0, count);
}

function generateRandomOrder() {
    const totalQuantity = Math.floor(Math.random() * 9000) + 1000; // Between 1000 and 10000
    const numSellers = Math.floor(Math.random() * 3) + 2; // 2 to 4 sellers
    const sellers = getUniqueSellers(numSellers);
    
    const splits = [];
    let remaining = totalQuantity;
    
    // Add a commission 50% of the time (between 2% and 10%)
    if (Math.random() > 0.5) {
        const commissionPct = (Math.floor(Math.random() * 8) + 2) / 100;
        const commissionAmt = Math.floor(totalQuantity * commissionPct);
        splits.push({ type: "Commission", amount: { quantity: commissionAmt, currency: "EUR" } });
        remaining -= commissionAmt;
    }
    
    // Distribute the rest among sellers
    for (let i = 0; i < numSellers; i++) {
        if (i === numSellers - 1) {
            splits.push({ type: "BalanceAccount", account: sellers[i], amount: { quantity: remaining, currency: "EUR" } });
        } else {
            const chunk = Math.floor(Math.random() * (remaining * 0.6)) + 100; // Take up to 60% of remaining
            splits.push({ type: "BalanceAccount", account: sellers[i], amount: { quantity: chunk, currency: "EUR" } });
            remaining -= chunk;
        }
    }
    
    return {
        totalAmount: { quantity: totalQuantity, currency: "EUR" },
        splits: splits
    };
}

const randomId = (prefix) => `${prefix}-${Math.floor(Math.random() * 1e12)}`;

function generateUuidV7() {
    const hex = () => Math.floor(Math.random() * 16).toString(16);
    const hexN = (n) => {
        let str = '';
        for (let i = 0; i < n; i++) str += hex();
        return str;
    };
    return `${hexN(8)}-${hexN(4)}-7${hexN(3)}-8${hexN(3)}-${hexN(12)}`;
}

// --- API Methods ---

function createPaymentIntent() {
    const url = `http://localhost/api/v1/payments`;

    const orderData = generateRandomOrder();

    const payload = JSON.stringify({
        orderId: randomId('ORDER'),
        buyerId: randomId('BUYER'),
        merchantAccount: MARKETPLACE,
        processingModel: "MARKETPLACE",
        totalAmount: orderData.totalAmount,
        splits: orderData.splits
    });

    const params = {
        headers: {

            'Authorization': `Bearer ${ACCESS_TOKEN}`,
            'Idempotency-Key': generateUuidV7(),
            'Content-Type': 'application/json'
        },
    };

    return http.post(url, payload, params);
}

function authorizePayment(paymentId) {
    const url = `http://localhost/api/v1/payments/${paymentId}/authorize`;

    const payload = JSON.stringify({
        paymentMethod: {
            type: "CardToken", // Matches your Sealed Class @JsonTypeInfo
            token: "tok_visa",
            cvc: "123"
        }
    });

    const params = {
        headers: {

            'Authorization': `Bearer ${ACCESS_TOKEN}`,
            'Content-Type': 'application/json'
        },
    };

    return http.post(url, payload, params);
}

// --- Main Scenario ---

export default function () {
    // --- STEP 1: Create Intent ---
    const intentRes = createPaymentIntent();

    const intentCheck = check(intentRes, {
        'intent created (201)': (r) => r.status === 201,
    });

    if (!intentCheck) {
        console.error(`Intent failed with status ${intentRes.status}: ${intentRes.body}`);
        return;
    }

    const body = intentRes.json();
    const paymentId = body.paymentIntentId; // Corrected from your DTO

    // --- STEP 2: Thinking Time (2-5s) ---
    sleep(Math.random() * (5 - 2) + 2);

    // --- STEP 3: Authorize ---
    const authRes = authorizePayment(paymentId);

    check(authRes, {
        'authorized (200/201)': (r) => r.status === 200 || r.status === 201,
    });

    console.log(`Success! Order ${body.orderId} processed via Payment ${paymentId}`);
}