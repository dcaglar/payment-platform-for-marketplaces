import { useState, useEffect, useRef } from 'react';
import { loadStripe } from '@stripe/stripe-js';
import { Elements } from '@stripe/react-stripe-js';
import { PaymentForm } from './components/PaymentElement';
import { TestCards } from './components/TestCards';
import { createPayment, pollPaymentStatus, authorizePayment } from './services/paymentService';
import { uuidV7 } from './services/uuidv7';
import './index.css';

const stripePromise = loadStripe(
  import.meta.env.VITE_STRIPE_PUBLISHABLE_KEY || 'pk_test_placeholder'
);

const STEPS = {
  FORM: 'form',
  CREATING: 'creating',
  PAYMENT: 'payment',
  AUTHORIZING: 'authorizing',
  SUCCESS: 'success',
  ERROR: 'error'
};

/**
 * Options for Stripe's card form ("finalize payments on the server" flow).
 * The form runs in deferred mode: it needs only the publishable key, the amount and the currency.
 * On "Pay Now" the page creates a Stripe PaymentMethod from the card (paymentMethodCreation: 'manual')
 * and sends its id to our authorize endpoint; payment-service confirms the PaymentIntent server-side.
 * captureMethod must match the PaymentIntent payment-service creates at Stripe (manual capture).
 * With the simulator (clientSecret sim_cs_...) the same form is used; the simulator ignores the card.
 */
function stripeElementsOptions(paymentData) {
  return {
    mode: 'payment',
    amount: paymentData.totalAmount.quantity,
    currency: paymentData.totalAmount.currency.toLowerCase(),
    captureMethod: 'manual',
    paymentMethodCreation: 'manual'
  };
}

function isSimulatedPsp(clientSecret) {
  return Boolean(clientSecret) && clientSecret.startsWith('sim_cs_');
}

function App() {
  // Form state
  const [orderId, setOrderId] = useState('ORDER-1');
  const [buyerId, setBuyerId] = useState('BUYER-1');
  const [merchantAccount, setMerchantAccount] = useState('MARKETPLACE-5');
  const [processingModel, setProcessingModel] = useState('MARKETPLACE');
  const [totalAmount, setTotalAmount] = useState('3000');
  const [currency, setCurrency] = useState('EUR');
  // type is 'BalanceAccount' (a seller, needs an account) or 'Commission' (the marketplace's cut)
  const [splits, setSplits] = useState([
    { id: 1, type: 'BalanceAccount', account: 'SELLER-5-1', amount: '1400' },
    { id: 2, type: 'Commission', account: '', amount: '100' },
    { id: 3, type: 'BalanceAccount', account: 'SELLER-5-2', amount: '1400' },
    { id: 4, type: 'Commission', account: '', amount: '100' }
  ]);

  // Payment flow state
  const [step, setStep] = useState(STEPS.FORM);
  const [paymentData, setPaymentData] = useState(null);
  const [clientSecret, setClientSecret] = useState(null);
  const [paymentIntentId, setPaymentIntentId] = useState(null);
  const [error, setError] = useState(null);
  const [retryCountdown, setRetryCountdown] = useState(null);
  const retryTimeoutRef = useRef(null);
  const retryIntervalRef = useRef(null);

  // One idempotency key per checkout attempt. It is reused for every resend of the same
  // request (second click, retry after a timeout, a network error or a 409), and dropped only
  // after a final answer: the payment intent was created, or 422 (key reused with another body).
  const checkoutKeyRef = useRef(null);
  const checkoutBodyRef = useRef(null);

  // Split management
  const addSplit = () => {
    const newId = Math.max(...splits.map(sp => sp.id), 0) + 1;
    setSplits([...splits, { id: newId, type: 'BalanceAccount', account: '', amount: '' }]);
  };

  const removeSplit = (id) => {
    if (splits.length > 1) {
      setSplits(splits.filter(sp => sp.id !== id));
    }
  };

  const updateSplit = (id, field, value) => {
    setSplits(splits.map(sp =>
      sp.id === id ? { ...sp, [field]: value } : sp
    ));
  };

  const totalCalculated = splits.reduce((sum, sp) => sum + (parseInt(sp.amount) || 0), 0);

  // Cleanup timers on unmount
  useEffect(() => {
    return () => {
      if (retryTimeoutRef.current) {
        clearTimeout(retryTimeoutRef.current);
      }
      if (retryIntervalRef.current) {
        clearInterval(retryIntervalRef.current);
      }
    };
  }, []);

  // ============================================
  // Payment Flow Handlers
  // ============================================

  /**
   * Handles 201 CREATED / 200 OK responses
   * Uses response body content to determine action (same HTTP status can have different meanings)
   */
  const handlePaymentCreated = async (payment) => {
    // Case 1: Payment created successfully with clientSecret
    if (payment.clientSecret && payment.paymentIntentId) {
      setClientSecret(payment.clientSecret);
      setPaymentIntentId(payment.paymentIntentId);
      setStep(STEPS.PAYMENT);
      return;
    }

    // Case 2: Payment declined
    if (payment.status === 'DECLINED') {
      setError('Payment was declined by the payment provider. Please try a different payment method.');
      setStep(STEPS.ERROR);
      return;
    }

    // Case 3: Payment pending (can happen with 200 REPLAYED when original was 202)
    if (payment.status === 'CREATED_PENDING' && payment.paymentIntentId) {
      await handlePaymentAccepted(payment);
      return;
    }

    // Case 4: Unexpected state
    setError('Payment created but client secret is not available. Please try again.');
    setStep(STEPS.ERROR);
  };

  /**
   * Handles 202 ACCEPTED - payment is processing asynchronously
   * Polls the status endpoint until clientSecret is available
   */
  const handlePaymentAccepted = async (payment) => {
    if (!payment.paymentIntentId) {
      setError('Payment ID missing');
      setStep(STEPS.ERROR);
      return;
    }

    setPaymentIntentId(payment.paymentIntentId);
    
    try {
      const pollResult = await pollPaymentStatus(payment.paymentIntentId);
      if (pollResult.payment?.clientSecret) {
        setClientSecret(pollResult.payment.clientSecret);
        setPaymentIntentId(pollResult.payment.paymentIntentId || payment.paymentIntentId);
        setStep(STEPS.PAYMENT);
      } else {
        throw new Error('Client secret not available after polling. Payment may still be processing.');
      }
    } catch (pollErr) {
      setError(getErrorMessage(pollErr) || 'Failed to poll payment status');
      setStep(STEPS.ERROR);
    }
  };

  /**
   * Handles 409 CONFLICT: the first request with this key is still being processed.
   * Waits Retry-After seconds (2 if the header is missing) and sends the SAME request
   * with the SAME key again.
   */
  const handleInProgress = (retryAfterSeconds, paymentRequest, idempotencyKey) => {
    setRetryCountdown(retryAfterSeconds);
    setError(`Payment is still being processed. Retrying in ${retryAfterSeconds} seconds...`);

    // Clear any existing retry
    if (retryTimeoutRef.current) clearTimeout(retryTimeoutRef.current);
    if (retryIntervalRef.current) clearInterval(retryIntervalRef.current);

    // Countdown timer
    let remaining = retryAfterSeconds;
    retryIntervalRef.current = setInterval(() => {
      remaining--;
      setRetryCountdown(remaining);
      if (remaining <= 0) {
        clearInterval(retryIntervalRef.current);
        retryIntervalRef.current = null;
      }
    }, 1000);

    // Schedule retry
    retryTimeoutRef.current = setTimeout(async () => {
      if (retryIntervalRef.current) {
        clearInterval(retryIntervalRef.current);
        retryIntervalRef.current = null;
      }
      setRetryCountdown(null);
      setError(null);
      setStep(STEPS.CREATING);
      await sendCreatePayment(paymentRequest, idempotencyKey);
    }, retryAfterSeconds * 1000);
  };

  /**
   * Extracts error message from error object
   */
  const getErrorMessage = (err) => {
    return err.data?.details?.message || err.data?.message || err.data?.error || err.message || 'An error occurred';
  };

  /**
   * Sends create payment and handles every answer.
   *   201 / 200 / 202 -> intent exists; the checkout attempt is finished, drop the key
   *   409             -> first request still running; retry with the same key
   *   422             -> this key was used with a different body; drop the key
   *   anything else   -> keep the key, so "Proceed to Checkout" again resends with it
   */
  const sendCreatePayment = async (paymentRequest, idempotencyKey) => {
    let result;
    try {
      result = await createPayment(paymentRequest, idempotencyKey);
    } catch (err) {
      if (err.status === 409) {
        const retrySeconds = parseInt(err.data?.headers?.['retry-after']) || 2;
        handleInProgress(retrySeconds, paymentRequest, idempotencyKey);
        return;
      }
      if (err.status === 422) {
        checkoutKeyRef.current = null;
        checkoutBodyRef.current = null;
        setError('This idempotency key was already used with a different request. Submit again to start a new checkout.');
        setStep(STEPS.ERROR);
        return;
      }
      setError(getErrorMessage(err) || 'Failed to create payment');
      setStep(STEPS.ERROR);
      return;
    }

    const { payment, status } = result;
    // the payment intent exists: this checkout attempt is done
    checkoutKeyRef.current = null;
    checkoutBodyRef.current = null;
    setPaymentData(payment);

    // HTTP 202 ACCEPTED → Always poll (payment is processing asynchronously)
    if (status === 202) {
      await handlePaymentAccepted(payment);
      return;
    }

    // HTTP 201 CREATED / 200 OK → Use response body content to decide
    // (Same HTTP status can mean: success with clientSecret, declined, or pending replay)
    if (status === 201 || status === 200) {
      await handlePaymentCreated(payment);
      return;
    }

    setError(`Unexpected HTTP status: ${status}`);
    setStep(STEPS.ERROR);
  };

  /** The request body payment-service expects (CreatePaymentIntentRequestDTO). */
  const buildPaymentRequest = () => {
    const paymentRequest = {
      orderId: orderId.trim(),
      buyerId: buyerId.trim(),
      merchantAccount: merchantAccount.trim(),
      processingModel: processingModel,
      totalAmount: { quantity: parseInt(totalAmount), currency }
    };
    // DIRECT_MERCHANT must have no splits; MARKETPLACE must have splits
    if (processingModel === 'MARKETPLACE') {
      const requestSplits = [];
      for (const sp of splits) {
        const split = { type: sp.type, amount: { quantity: parseInt(sp.amount), currency } };
        if (sp.type === 'BalanceAccount') {
          split.account = sp.account.trim();
        }
        requestSplits.push(split);
      }
      paymentRequest.splits = requestSplits;
    }
    return paymentRequest;
  };

  // Payment creation entry point
  const handleSubmit = async (e) => {
    e.preventDefault();
    setError(null);
    setStep(STEPS.CREATING);

    const paymentRequest = buildPaymentRequest();
    const body = JSON.stringify(paymentRequest);

    // Same request as the unfinished attempt → same key (the server answers it at most once).
    // Different request → a new checkout attempt → a new key.
    if (checkoutKeyRef.current === null || checkoutBodyRef.current !== body) {
      checkoutKeyRef.current = uuidV7();
      checkoutBodyRef.current = body;
    }

    await sendCreatePayment(paymentRequest, checkoutKeyRef.current);
  };

  // Payment authorization
  const handleAuthorize = async (paymentMethodId) => {
    if (!paymentIntentId) {
      setError('Payment ID missing');
      return;
    }

    setStep(STEPS.AUTHORIZING);
    setError(null);

    try {
      const result = await authorizePayment(paymentIntentId, paymentMethodId);
      const { status } = result.payment;

      if (status === 'AUTHORIZED' || status === 'SUCCEEDED') {
        setStep(STEPS.SUCCESS);
      } else if (status === 'PENDING_AUTH') {
        // 202: not decided yet. A 3-D Secure card also ends here: this demo has no authentication step.
        setError('Payment not confirmed yet (PENDING_AUTH). If the card needs 3-D Secure, it stays pending: this demo has no authentication step.');
        setStep(STEPS.ERROR);
      } else if (status === 'DECLINED' || status === 'FAILED') {
        setError(result.payment.error?.message || 'Payment declined');
        setStep(STEPS.ERROR);
      } else {
        setStep(STEPS.SUCCESS);
      }
    } catch (err) {
      setError(getErrorMessage(err) || 'Failed to authorize payment');
      setStep(STEPS.ERROR);
    }
  };

  const handleReset = () => {
    // Clear any pending retries
    if (retryTimeoutRef.current) {
      clearTimeout(retryTimeoutRef.current);
      retryTimeoutRef.current = null;
    }
    if (retryIntervalRef.current) {
      clearInterval(retryIntervalRef.current);
      retryIntervalRef.current = null;
    }
    // Reset all state
    setStep(STEPS.FORM);
    setPaymentData(null);
    setClientSecret(null);
    setPaymentIntentId(null);
    setError(null);
    setRetryCountdown(null);
  };

  return (
    <div className="app">
      <div className="header">
        <h1>💳 Payment Checkout</h1>
      </div>

      {step === STEPS.FORM && (
        <div className="container">
          <div className="card">
            <h2>Order Details</h2>
            {error && <div className="error-message">{error}</div>}
            
            <form onSubmit={handleSubmit}>
              <div className="form-group">
                <label>Order ID *</label>
                <input type="text" value={orderId} onChange={(e) => setOrderId(e.target.value)} required />
              </div>

              <div className="form-group">
                <label>Buyer ID *</label>
                <input type="text" value={buyerId} onChange={(e) => setBuyerId(e.target.value)} required />
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
                <div className="form-group">
                  <label>Merchant Account *</label>
                  <input type="text" value={merchantAccount} onChange={(e) => setMerchantAccount(e.target.value)} required />
                </div>
                <div className="form-group">
                  <label>Processing Model *</label>
                  <select value={processingModel} onChange={(e) => setProcessingModel(e.target.value)}>
                    <option value="MARKETPLACE">MARKETPLACE (with splits)</option>
                    <option value="DIRECT_MERCHANT">DIRECT_MERCHANT (no splits)</option>
                  </select>
                </div>
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: '12px' }}>
                <div className="form-group">
                  <label>Total Amount (cents) *</label>
                  <input 
                    type="number" 
                    value={totalAmount} 
                    onChange={(e) => setTotalAmount(e.target.value)} 
                    min="1" 
                    required 
                  />
                  {processingModel === 'MARKETPLACE' && (
                    <small style={{ color: '#666', fontSize: '12px', display: 'block', marginTop: '4px' }}>
                      Sum of splits: {totalCalculated}
                    </small>
                  )}
                </div>
                <div className="form-group">
                  <label>Currency *</label>
                  <select value={currency} onChange={(e) => setCurrency(e.target.value)}>
                    <option value="EUR">EUR</option>
                    <option value="USD">USD</option>
                    <option value="GBP">GBP</option>
                  </select>
                </div>
              </div>

              {processingModel === 'MARKETPLACE' && (
                <>
                  <h3 style={{ marginTop: '24px', marginBottom: '12px', fontSize: '16px' }}>Splits</h3>

                  {splits.map((sp, index) => (
                    <div key={sp.id} className="seller-line">
                      <div className="seller-line-header">
                        <h3>Split #{index + 1}</h3>
                        {splits.length > 1 && (
                          <button
                            type="button"
                            onClick={() => removeSplit(sp.id)}
                            className="remove-btn"
                          >
                            Remove
                          </button>
                        )}
                      </div>
                      <div className="seller-line-fields">
                        <div className="form-group">
                          <label>Type *</label>
                          <select value={sp.type} onChange={(e) => updateSplit(sp.id, 'type', e.target.value)}>
                            <option value="BalanceAccount">Seller (BalanceAccount)</option>
                            <option value="Commission">Commission</option>
                          </select>
                        </div>
                        {sp.type === 'BalanceAccount' && (
                          <div className="form-group">
                            <label>Seller ID *</label>
                            <input
                              type="text"
                              value={sp.account}
                              onChange={(e) => updateSplit(sp.id, 'account', e.target.value)}
                              placeholder="SELLER-5-1"
                              required
                            />
                          </div>
                        )}
                        <div className="form-group">
                          <label>Amount (cents) *</label>
                          <input
                            type="number"
                            value={sp.amount}
                            onChange={(e) => updateSplit(sp.id, 'amount', e.target.value)}
                            min="1"
                            required
                          />
                        </div>
                      </div>
                    </div>
                  ))}

                  <button
                    type="button"
                    onClick={addSplit}
                    className="add-seller-btn"
                  >
                    + Add Split
                  </button>
                </>
              )}

              <button type="submit" className="submit-btn">Proceed to Checkout</button>
            </form>
          </div>
        </div>
      )}

      {step === STEPS.CREATING && (
        <LoadingScreen 
          message={
            retryCountdown !== null 
              ? `Payment is still being processed. Retrying in ${retryCountdown} seconds...`
              : "Creating payment intent..."
          } 
        />
      )}

      {step === STEPS.PAYMENT && clientSecret && (
        <div className="container">
          <div className="card">
            <h2>💳 Payment</h2>
            {paymentData && (
              <div className="order-summary">
                <p><strong>Order:</strong> {paymentData.orderId}</p>
                <p><strong>Amount:</strong> {(paymentData.totalAmount.quantity / 100).toFixed(2)} {paymentData.totalAmount.currency}</p>
              </div>
            )}
            {error && <div className="error-message">{error}</div>}
            <Elements stripe={stripePromise} options={stripeElementsOptions(paymentData)}>
              <PaymentForm onPaymentSubmit={handleAuthorize} onError={(err) => setError(err.message)} />
            </Elements>
            <TestCards simulated={isSimulatedPsp(clientSecret)} />
          </div>
        </div>
      )}

      {step === STEPS.AUTHORIZING && (
        <LoadingScreen message="Confirming payment..." />
      )}

      {step === STEPS.SUCCESS && (
        <div className="container">
          <div className="card">
            <h2>✅ Success!</h2>
            <div style={{ textAlign: 'center', padding: '40px' }}>
              <div style={{ fontSize: '48px', marginBottom: '20px' }}>🎉</div>
              <p>Payment processed successfully</p>
              {paymentData && (
                <div className="order-summary" style={{ textAlign: 'left', margin: '20px 0' }}>
                  <p><strong>Order:</strong> {paymentData.orderId}</p>
                  <p><strong>Amount:</strong> {(paymentData.totalAmount.quantity / 100).toFixed(2)} {paymentData.totalAmount.currency}</p>
                </div>
              )}
              <button onClick={handleReset} className="submit-btn" style={{ marginTop: '20px' }}>
                New Payment
              </button>
            </div>
          </div>
        </div>
      )}

      {step === STEPS.ERROR && (
        <div className="container">
          <div className="card">
            <h2>❌ Error</h2>
            {error && <div className="error-message">{error}</div>}
            <div style={{ textAlign: 'center', padding: '20px' }}>
              <button onClick={handleReset} className="submit-btn">Try Again</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// Reusable loading component
function LoadingScreen({ message }) {
  return (
    <div className="container">
      <div className="card">
        <h2>⏳ {message}</h2>
        <div style={{ textAlign: 'center', padding: '40px' }}>
          <div className="loading-spinner" style={{ width: '40px', height: '40px', margin: '0 auto 20px' }}></div>
          <p>{message}</p>
        </div>
      </div>
    </div>
  );
}

export default App;
