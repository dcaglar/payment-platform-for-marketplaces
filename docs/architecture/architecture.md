# 🟦 Event-Driven Payments & Ledger Infrastructure for Multi-Seller Platforms

This project represents a backend **payment platform for  Merchant-of-Record (MoR) environment**.  
Think of a multi-seller e-commerce marketplace where shoppers can buy items from different sellers in a single checkout.  
The platform manages the **full payment lifecycle**: synchronous authorization, multi-seller decomposition, seller-level operations, and internal financial accounting.

---


# 🟦   High Level Plaform Arhictecture

### Full System Context Diagram(Create Payment Intent/Authorization flows are merged here for the sake of simplicity, please check below detailed authorization flow )
```mermaid
flowchart TD
    %% Define Color Styles
    classDef edgeBg fill:#ffe6e6,stroke:#333,stroke-width:1px;
    classDef internalBg fill:#e6f2ff,stroke:#99ccff,stroke-width:2px;
    classDef greenBox fill:#ccffcc,stroke:#333,stroke-width:1px;
    classDef pinkBox fill:#ffcccc,stroke:#333,stroke-width:1px;
    classDef yellowBox fill:#ffeb99,stroke:#333,stroke-width:1px;
    classDef whiteBox fill:#ffffff,stroke:#333,stroke-width:1px;
    classDef db fill:#e6ffe6,stroke:#ff6666,stroke-width:px;
    classDef topic fill:#8e7cc3,stroke:#ff6666,stroke-width:px;
    classDef consumers fill:#c27ba0,stroke:#ff6666,stroke-width:px;
    
    %% Kafka/Job Style (The "CaptureCommandExecutor" green style)
    classDef actionGreen fill:#ccffcc,stroke:#333,stroke-width:1px;

    subgraph External ["External Edge Host-Stateless payment acceptance service high availibility"]
        style External fill:transparent,stroke:none,color:#cc0000,font-weight:bold

        subgraph EdgeLayer ["EXTERNAL HOSTS (Edge Layer)"]
            style EdgeLayer fill:#ffffff,stroke:#333

            subgraph Cell1 ["Edge Cell 1"]
                style Cell1 fill:#ffe6e6,stroke:#333
                
                PAS1["Payment Acceptance<br/>Service"]:::yellowBox
                ID1["1. Idempotency Check<br/>⬇<br/>IdempotencyCheck"]:::greenBox
                PSP1(["External PSP APIs<br/>(Synchronous Auth)"]):::whiteBox
                DB1[("Edge Local db<br/>(PaymentIntent<br/>IdempotencyRecord<br/>OutboxEvent)")]:::db
                JOB1["LocalOutboxStoreAndForwardJob"]:::actionGreen

                PAS1 --> ID1
                PAS1 -- "2a. Synchronous Auth" --> PSP1
                PSP1 -- "2b. Persist Auth to Outbox" --> DB1
                PAS1 -- "3. Capture / Refund" --> DB1
                DB1 --> JOB1
            end

            subgraph Cell2 ["Edge Cell 2"]
                style Cell2 fill:#ffe6e6,stroke:#333
                
                PAS2["Payment Acceptance<br/>Service"]:::yellowBox
                ID2["1. Idempotency Check<br/>⬇<br/>IdempotencyCheck"]:::greenBox
                PSP2(["External PSP APIs<br/>(Synchronous Auth)"]):::whiteBox
                DB2[("Edge Local db<br/>(PaymentIntent<br/>IdempotencyRecord<br/>OutboxEvent)")]:::db
                JOB2["LocalOutboxStoreAndForwardJob"]:::actionGreen

                PAS2 --> ID2
                PAS2 -- "2a. Synchronous Auth" --> PSP2
                PSP2 -- "2b. Persist Auth to Outbox" --> DB2
                PAS2 -- "3. Capture / Refund" --> DB2
                DB2 --> JOB2
            end
        end
    end

    subgraph Internal ["INTERNAL HOST"]
        style Internal fill:#e6f2ff,stroke:#99ccff,color:#cc0000,font-weight:bold

        CDB[("Central OutboxEvent DB<br/>(OutboxEvent<br/>(Payment</br>Tx , JournalEntry<br/>Posting,</br>Account</brAccountBalance)")]:::db
        RELAY["Polls OutboxEvents<br/>OutboxRelayJob(s)"]:::greenBox

        JOB1 --> CDB
        JOB2 --> CDB
        CDB --> RELAY

        subgraph Kafka ["Kafka Cluster"]
            style Kafka fill:#f1c232,stroke:#333
            
            K1{{"gateway.capture.commands<br/>&lt;CaptureRequested&gt;"}}:::topic
            K2{{"gateway.capture.submitted<br/>&lt;CaptureSubmitted&gt;"}}:::topic
            K3{{"payment.psp.results<br/>EventEnvelope&lt;PaymentAuthorized&gt;,<br/>EventEnvelope&lt;CaptureConfirmed&gt;,<br/>EventEnvelope&lt;InternalTransferCommand&gt;"}}:::topic
            K4{{"journal.entries.recorded<br/>&lt;JournalEntriesRecorded&gt;"}}:::topic

        end

        %% Link 0
        RELAY -- "OutboxEvent&lt;PAYMENT_AUTHORIZED&gt; -> EventEnvelope&lt;PaymentAuthorized&gt; and publish " --> K3
        %% Link 1
        RELAY -- "OutboxEvent&lt;CAPTURE_REQUESTED&gt; -> EventEnvelope&lt;CaptureRequested&gt; and publish " --> K1
        %% Link 2
         RELAY -- "OutboxEvent&lt;CAPTURE_SUBMITTED&gt; -> EventEnvelope&lt;CaptureSubmitted&gt; and publish " --> K2
        %% Link 3
        RELAY -- "OutboxEvent&lt;CAPTURE_CONFIRMED&gt; -> EventEnvelope&lt;CaptureConfirmed&gt; and publish " --> K3
        %% Link 4
        RELAY -- "OutboxEvent&lt;JOURNAL_ENTRIES_RECORDED&gt; -> EventEnvelope&lt;JournalEntriesRecorded&gt; and publish " --> K4
        %% Link 5
        RELAY -- "OutboxEvent&lt;INTERNAL_TRANSFER_COMMAND&gt; -> EventEnvelope&lt;InternalTransferCommand&gt; and publish " --> K3
        %% Link 6
        RELAY -- "OutboxEvent&lt;SETTLEMENT_RECEIVED&gt; -> EventEnvelope&lt;SettlementReceived&gt; and publish " --> K3

        subgraph Consumers ["Payment Consumers (payment-consumers)"]
            style Consumers fill:#f2f2f2,stroke:#333
            
            C_CCE["CaptureCommandExecutor"]:::consumers
            C_CPC["CapturePspPerformedConsumer"]:::consumers
            C_PRC["PspResultConsumer"]:::consumers
            C_GCA["GrossCaptureAllocationConsumer"]:::consumers
            C_ABC["AccountBalanceConsumer"]:::consumers
           C_SDR["SimulatedSdrStreamingProcessorConsumer"]:::consumers

        end

        K1 --> C_CCE 
        K2 --> C_CPC 
        K3 --> C_PRC
        K4 --> C_ABC
        K4 --> C_GCA
         K4 --> C_SDR
 
        C_CCE -- "psp.asynCapure</br>append OutboxEvent&lt;CAPTURE_SUBMITTED&gt;" -->  CDB  
        C_GCA -- "listen for Capture JournalEntries,based on the presence of payment-split, it does perform idempotent update on Transfer, InternalTransferTx,and OutboxEvent(InternalTransferCommand)" --> CDB
        C_PRC --> CDB
        C_CPC --> CDB
        C_GCA --> CDB
        C_SDR --> CDB


        linkStyle 13,14,15,16,17,18,19 stroke:#6a0dad,stroke-width:3px;
        linkStyle 20,21,22,23,24,25 stroke:#f44336,stroke-width:2px;
        linkStyle 26,27,28,29,30,31 stroke:#8fce00,stroke-width:5px;
end
```


### Authorization Flow (This illustrates a demo checkout PCI compliant checkout page
##Please note that in direct integrations merchant will do 2 http call
   1-Sending  POST request on /api/v1/payments(with JWT & Idempotency-Key),a response with paymentintentid returned to merchant
   2-Using the payment intentid  in the response of step-1 , merhcant calls  POST /api/v1/payments/{paymentIntentId}/authorize(with JWT))
```mermaid
sequenceDiagram
    autonumber

    box rgb(240, 248, 255) "Client Layer"
        actor Shopper
        participant Browser as Shopper's Browser<br/>(React App)
    end

    box rgb(255, 240, 245) "Gateway Layer"
        participant Proxy as Backend Proxy<br/>(Node.js)
        participant Keycloak
    end

    box rgb(255, 244, 225) "Payment Edge Cell"
        participant PaymentSvc as payment-service<br/>(REST API)
        participant EdgeDB as edge-db<br/>(PostgreSQL)
    end

    box rgb(255, 235, 238) "External Systems"
        participant Stripe
    end

    %% Step 1: Create Payment Intent
    Note over Shopper, Stripe: Phase 1: Create Payment Intent & Prepare Checkout Form

    Shopper->>Browser: Fills cart details, clicks "Proceed to Checkout"
    Browser->>Proxy: POST /api/checkout/process-payment<br/>(with cart data & Idempotency-Key)
    
    Proxy->>Keycloak: Request service token (client_credentials)
    Keycloak-->>Proxy: Return JWT Access Token

    Proxy->>PaymentSvc: POST /api/v1/payments<br/>(with JWT & Idempotency-Key)
    
    %% --- IDEMPOTENCY FLOW ---
    PaymentSvc->>EdgeDB: SELECT response FROM idempotency_keys
    alt First Request (Key is new)
        EdgeDB-->>PaymentSvc: Not Found
        PaymentSvc->>PaymentSvc: Create PaymentIntent (status=CREATED_PENDING)
        PaymentSvc->>EdgeDB: INSERT INTO payment_intents
        
        par Async Stripe Call
            PaymentSvc->>Stripe: Create PaymentIntent (API Call)
        and Wait for Result
            PaymentSvc->>PaymentSvc: Wait up to 3 seconds
        end

        alt Stripe Responds < 3s
            Stripe-->>PaymentSvc: Return { id, clientSecret }
            PaymentSvc->>PaymentSvc: Update PaymentIntent (status=CREATED)
            PaymentSvc->>EdgeDB: INSERT response INTO idempotency_keys
            PaymentSvc-->>Proxy: 201 Created<br/>{ paymentIntentId, clientSecret }
        else Timeout (> 3s)
            PaymentSvc-->>Proxy: 202 Accepted (Retry-After: 2s)<br/>{ paymentIntentId, clientSecret: null }
            
            Note over Proxy, PaymentSvc: Client enters polling loop
            
            loop Polling
                Proxy->>PaymentSvc: GET /payments/{id}
                PaymentSvc-->>Proxy: 200 OK { ... }
            end

            Note right of PaymentSvc: Background Thread
            Stripe-->>PaymentSvc: Return { id, clientSecret } (Delayed)
            PaymentSvc->>PaymentSvc: Update PaymentIntent (status=CREATED)
        end

    else Retry (Key already processed)
        EdgeDB-->>PaymentSvc: Return stored JSON response
        PaymentSvc-->>Proxy: 200 OK (Replayed)<br/>{ paymentIntentId, clientSecret }
    end
    %% --- END IDEMPOTENCY FLOW ---

    Proxy-->>Browser: Return { clientSecret }

    %% Step 2: Collect Card Details via Stripe Element
    Note over Shopper, Stripe: Phase 2: Securely Collect Card Details

    Browser->>Stripe: Stripe.js initializes Payment Element using clientSecret
    Stripe-->>Browser: Renders secure card input form (iframe)
    
    Shopper->>Browser: Enters card details into Stripe's form
    Note right of Shopper: Card data goes directly to Stripe,<br/>never touching any of our servers.

    %% Step 3: Confirm Payment with Stripe and Authorize Internally
    Note over Shopper, Stripe: Phase 3: Confirm Payment & Finalize State

    Shopper->>Browser: Clicks "Pay Now"
    Browser->>Stripe: elements.submit() (Tokenize & Associate)
    Note right of Browser: Stripe JS sends card data,<br/>creates PaymentMethod,<br/>links it to PaymentIntent
    Stripe-->>Browser: Validation OK
    Browser->>Proxy: POST /api/checkout/authorize-payment/{paymentId}
    
    Proxy->>Keycloak: Request service token (can be cached)
    Keycloak-->>Proxy: Return JWT Access Token

    Proxy->>PaymentSvc: POST /api/v1/payments/{paymentId}/authorize
    
    PaymentSvc->>Stripe: paymentIntents.confirm(id)
    Stripe-->>PaymentSvc: SUCCEEDED

    rect rgb(230, 240, 255)
        note over PaymentSvc: @Transactional
        PaymentSvc->>PaymentSvc: Update PaymentIntent status to AUTHORIZED
        PaymentSvc->>PaymentSvc: Save PaymentAuthorized to Outbox table
    end

    PaymentSvc-->>Proxy: 200 OK { status: 'AUTHORIZED' }
    Proxy-->>Browser: Return final success status
    Browser->>Shopper: Display "Payment Successful" message
```


# 🟩 Key Clarifications (MoR Model)


### **1. Is the payment platform internal?**
Yes. The payment platform is an **internal backend domain service**, not exposed to shoppers directly. While it provides endpoints like `POST /api/v1/payments/{paymentId}/authorize`, these are meant to be called by your own internal proxies or checkout services, never directly by the shopper's browser.

---

### **2. Do we perform the actual financial authorization ourselves?**
No. Even though we expose an `/authorize` endpoint to orchestrate the flow, we do not perform the actual financial authorization. We simply act as a gateway to trigger and record the authorization happening at an external PSP (like Stripe).  
From the PSP’s perspective, we appear as a **single merchant-of-record**; seller details remain completely internal to our ledger.

---

### **3. Do we distribute funds to sellers internally?**
Yes. As the MoR, the platform manages all **fund allocation**, applies platform fees, credits seller balances, and schedules payouts.  
The PSP simply transfers funds into the MoR account.

---

### **4. Why separate PaymentIntent and Payment?**
PaymentIntent is just a domain entity living in edge layer and edge db so its not a global entity,but Payment is part of central cluster and it is the real entity created after a financial ionteraction with external world

# 🟧 Functional Requirements
*(written using Shopper, Seller, and Internal Services as actors)*

## **For Shoppers**

### **FR1 — Shoppers should be able to make a payment for a multi-seller basket.**
- A shopper must be able to proceed to checkout page(cretePaymentIntent), and then pay via clicking pay button on checkout page(authorize endpoint)

### **FR2 — Shoppers should be able to see accurate payment authorization status.**
- Shoppers should be able to view whether their payment is authorized or declined, its a syncronous psp call, and shoppers can see payment status via the paymentintent

---




## **For Sellers**

### **FR3 — Sellers should be able to receive their portion of a shopper’s payment if defined in splits array
- Each seller must receive the correctly allocated share of the total payment based on the items purchased from them.

### **FR4 — Sellers should be able to view their financial state.**
- Sellers should be able to access their balances, payable amounts, and payout summaries via projected views

---

## **For Internal Services (Checkout / Order / Finance / Payouts)**

### **FR5 — Checkout/Order Service should be able to create a PaymentIntent.**
- It must be possible for the Order Service to create a PaymentIntent and obtain the generated Intent along with its seller-level PaymentSplits.

### **FR6 — Checkout/Order Service should be able to trigger authorization via PSP.**
- The system must allow Checkout to authorize the total payment amount through an external PSP.

### **FR7 — Internal services should be able to perform operations.**
- Internal services must be able to request captures, cancellations, and refunds *per Payment*.

### **FR8 — The system must maintain internal fund distribution for reporting and payouts.**
- Internal components (Finance, Payouts) must be able to retrieve seller payables, platform fees, and other financial allocations.


---

# 🟥 Non-Functional Requirements
*(written using “The system should be…” statements)*

### **NFR1 — The system should be highly available.**
Payment creation and authorization must remain available during peak checkout traffic.

### **NFR2 — The system should ensure strong consistency for financial data.**
State transitions must never lead to incorrect balances or double charges.

### **NFR3 — The system should be secure.**
Sensitive financial data must be protected using proper authentication, authorization, and encryption.

### **NFR4 — The system should be observable.**
Logs, metrics, and tracing must allow operators to understand system behavior and diagnose issues.

### **NFR5 — The system should be scalable.**
It must support increasing transaction volumes, sellers, and asynchronous workflows without degradation.

### **NFR6 — The system must be correct under retries and failures.**
Even under retries, restarts, and network issues, financial outcomes must remain correct.

---

# 🟦 Architecture Summary (Non-Functional / Implementation Section)

The platform internally uses:
- **Event-driven architecture** for asynchronous flows for capture,refund,paymentsplits, transfers and the ledger flow
- **Kafka topics** for execution queuing and PSP results
- **Idempotent state transitions** to ensure correctness under retries
- **Double-entry ledger** for immutable financial history
- **PSP gateway client** for authorization, capture, refund, and cancel operations
- **Internal balance tracking** for seller payables and platform revenues

---

# 🟦 Core Entities (Domain-Level)

These represent the nouns our system uses to satisfy the functional requirements.  
They define the **data model**, the **API vocabulary**, and the **business language** of the Merchant-of-Record payment platform.

---

## 🧍 Actors

### **Shopper**
The end-user making a purchase across one or multiple sellers.

### **Seller**
A marketplace participant who receives part of the shopper’s payment and later receives payouts.

### **Internal Services**
- Checkout / Order Service
- Finance

These actors perform operations on payments, orders, balances, and payouts.

---

# 🟩 Core Business Entities
Here you can see t journey of a paymentintent -> pamyent- > capture/refund/ settle/ payout/ split transaction


These are the fundamental nouns of our Merchant-of-Record payment platform.

---

![UML Diagram](um-diagram-payment-ledger-domain.png)



## **1. PaymentIntent**

Represents the **shopper's intent to pay** for a multi-seller basket.

**When it's created:**
- Step 1: Shopper initiates checkout → `POST /api/v1/payments` endpoint
- Created with status `CREATED_PENDING` (initially), then transitions to `CREATED` once Stripe ID is obtained.
- Contains: `buyerId`, `orderId`, `totalAmount`, `paymentSplits` (seller breakdown)

**Why it exists:**
- Separates "intent to pay"(living in its own edge) from "actual payment transaction(payment-living in the central cluster)"
- Enables idempotent authorization attempts (prevents duplicate PSP calls)
- Supports retry logic for transient PSP failures


## **2. Payment**

Represents the **actual financial transaction** created after authorization succeeds

**When it's created:**
- Created centrally by the `PspResultConsumer` when the PSP authorization response is `AUTHORIZED`.

**Why it exists:**
- Models actual money movement (vs. PaymentIntent which is just "intent")
- Tracks aggregate-level financial state across all sellers
- Provides aggregate view: total captured, total refunded
- Links to `PaymentIntent` via `paymentIntentId` for traceability
- Enables financial reporting and reconciliation



## **3. OutboxEvent**
Represents **immutable integration events** stored locally on edge or centrally, it simply stores the deserilazed byte of an any EventEnvelope<Event>

**When it's created:**
  When intent mutations or external network results are received.

**Why it exists:**
- Guarantees exactly-once publishing through the database transaction.
- Eliminates dual-write vulnerabilities between DB and Kafka.

---

## **4. Payment Transaction (Tx)**

Represents an **individual interaction with an external PSP** executed against a Payment (e.g., `AuthorizationTx`, `CaptureTx`, `RefundTx`, `SettleTx`). 

**When it's created:**
  - Whenever an operation is requested and executed against the external PSP (e.g., when we successfully call external psp capture api, a `CaptureTx` is recorded).

**Why it exists:**
  - **Audit & History:** While the `Payment` aggregate tracks the *cumulative total* (e.g., "This payment has 100 EUR captured"), the `Tx` tracks the *individual external psp interactions* ("We did call external psp capture api with this parameters, and this was the response from them").
  - **External PSP Linking:** It stores the external network identifiers (like the PSP's acquirer reference) to trace exactly which network call caused the balance change.
  - **JournalEntry record Bridging to actual external PSP interaction:** It actually is the proof of the transaction when we look at our JournalEntry and understand why we have this journal entry in our system,because of this external interaction with psp

## **5. JournalEntry**
Represents a **single double-entry journal entry**.It is always true that sum of the debit and credit postings are equal

Contains:
- Debit postings
- Credit postings
- JournalId
- Timestamp
- TxId(which is the linking this JournalEntry  to the exact external psp interaction record ) (paymentIntentId, txId, sellerId, etc.)

**Why it exists:**  
Ensures financial correctness, auditability, and immutable accounting history.

---

## **6. Posting (Debit / Credit)**

A component of a LedgerEntry.

- Refers to an account
- Contains a signed amount
- Reflects accounting direction (DR/CR)

**Why it exists:**  
JournalEntries consist of multiple postings — always balanced.

---

## **7. Account**

Represents a **financial account** in the internal ledger, such as:

- PLATFORM_CASH
- PSP_RECEIVABLES
- AUTH_RECEIVABLE
- AUTH_LIABILITY
- MERCHANT_GROSS_CAPTURE_SUSPENSE
- BALANCE_ACCOUNT
- PLATFORM_COMMISSION_ESCROW
- PLATFORM_OPERATIONAL_REVENUE
- PSP_FEE_EXPENSE

**Why it exists:**  
Money moves internally between accounts, not as free-form variables.

---

## **8. Balance**

Represents the **current financial standing** of an account (e.g., seller’s accrued revenue).  
Derived from applied LedgerEntries.

**Why it exists:**  
Used for reporting, analytics, payouts, and consistency validation.








TYou can see here sequence diagram  of shopper a, and payment journey end to end, also global idempotency hanlding 



### Simplified Consumer Architecture

![Architecture](https://github.com/dcaglar/ecommerce-platform-kotlin/blob/25b863edd5fc15647c14b70f55703e3494bf6e91/docs/architecture/async-payment-prrocessing.png)
A new simplified Kafka consumer architecture has been introduced to streamline PSP operations and double-entry bookkeeping.

**Why we moved away from the "Consume-Process-Publish" pattern:**
Historically, consumers would read an event, process it (e.g. call a PSP), and then immediately publish a new event using Kafka Transactions. This attempted to achieve "exactly-once" delivery semantics but caused significant issues:
- **Abusing Kafka as a Database**: Relying on Kafka transactions to guarantee state consistency across external API calls and database commits led to fragile, blocking architectures.
- **Blocking Calls in Transactions**: External PSP calls (which can be slow) held open Kafka transactions, reducing throughput and risking transaction timeouts.
- **Unrealistic Exactly-Once Guarantees**: Achieving true exactly-once semantics across a database, an external HTTP API, and Kafka is impossible without distributed locks or 2PC (Two-Phase Commit).

**The New Pattern (Outbox-Driven Consumers):**
1. **Intents (Capture/Refund Received)**: `CaptureReceived` events denote that an intent to capture has been recorded in the database edge outbox.
2. **Executors (`CaptureCommandExecutor`)**: These components listen to `capture-execution-queue`. They perform the synchronous call to the external PSP Gateway. Upon receiving a terminal or retryable result, they **do not publish back to Kafka directly**. Instead, they write a `ExternalAsyncCaptureToPspPerformed` event into the Central Database Outbox.
3. **Outbox Relay**: The `OutboxRelayJob` reads these results from the database outbox and publishes them asynchronously to their respective Kafka topics (e.g., `psp-result-queue`, `capture-execution-queue`, `internal-transfer-queue`, `journal.entries.recorded`) based on the event type.
4. **Result Processing (`PspResultConsumer`)**: Listens to the `psp-result-queue` to apply the results to the central database, finalize payment statuses, trigger internal double-entry ledger bookkeeping, and schedule any required internal transfers.

### Psp Result Consumer Detailed Flow ( NOT %100 ACCURATE/DRAFT WIP)
```mermaid
graph TD
%% Define Styles
classDef branch fill:#f9f9f9,stroke:#333,stroke-width:2px;
classDef atomic fill:#e1f5fe,stroke:#0277bd,stroke-width:2px;

    %% Branch definitions
    subgraph AuthBranch ["Branch: processAuthorized"]
        A_State[("Payment: Initialize<br/>Status: AUTH")]:::branch
        A_Tx[("Tx: Create AuthTx<br/>Status: SUCCESS")]:::branch
        A_Ledger["JournalEntry: authHold<br/>(DR: Receivable / CR: Liability)"]:::branch
        A_Outbox["Outbox: CaptureRequested"]:::branch
    end

    subgraph CaptureBranch ["Branch: processCaptureConfirmed"]
        B_State[("Payment: ApplyCapture<br/>Status: CAPTURED")]:::branch
        B_Tx[("Tx: CaptureTx<br/>Status: SUCCESS")]:::branch
        B_Ledger["JournalEntry: captureGrossAsset<br/>(DR: Liab / CR: Pool)"]:::branch
        B_Outbox["Outbox: JournalEntriesRecorded"]:::branch
    end

    subgraph TransferBranch ["Branch: processInternalTransferCommand"]
        C_State[("Payment: (No State Change)")]:::branch
        C_Tx[("Tx: InternalTransferTx<br/>Status: SUCCESS")]:::branch
        C_Ledger["JournalEntry: (Commission/Transfer/Revenue)<br/>(Recipe-specific DR/CR)"]:::branch
        C_Outbox["Outbox: JournalEntriesRecorded"]:::branch
    end

    subgraph SettleBranch ["Branch: processSettlementLineReconciled"]
        D_State[("Payment: reconcileCaptureSettlement<br/>(Status: SETTLED)")]:::branch
        D_Tx[("Tx: SettleTx<br/>Status: SUCCESS")]:::branch
        D_Ledger["JournalEntry: settlementLineItem<br/>(DR: Cash & Fee Exp / CR: PSP Recv)"]:::branch
        D_Outbox["Outbox: JournalEntriesRecorded"]:::branch
    end

    %% Persistence
    Persistence[("Atomic Commit (CentralDb)")]:::atomic

    %% Link all branches to persistence
    AuthBranch --> Persistence
    CaptureBranch --> Persistence
    TransferBranch --> Persistence
    SettleBranch --> Persistence

    %% Connections within branches (example for Auth)
    A_State & A_Tx & A_Ledger & A_Outbox --> Persistence
    B_State & B_Tx & B_Ledger & B_Outbox --> Persistence
    C_State & C_Tx & C_Ledger & C_Outbox --> Persistence
    D_State & D_Tx & D_Ledger & D_Outbox --> Persistence
```



### AccountBalanceConsumer Details ( TODO )


###  CaptureCommandExecutor/RefundCommandExecutor Details ( TODO )

###  GrossCaptureAllocationConsumer Details ( TODO )

###  SimulatedSdrStreamingProcessorConsumer Details ( TODO )







# 🟦 System Design & Modular Architecture

The platform follows a **Hexagonal (Ports & Adapters)** pattern to separate business policy from technical details, ensuring high availability (NFR1) and consistency (NFR2).

### **1. `payment-domain` (Core Business Logic)**
- **Role**: Pure Kotlin business rules and data models.
- **Components**: Entities (`Payment`, `PaymentIntent`), Value Objects, and Domain Events.
- **Traits**: Zero dependencies on Spring or MyBatis. Implements **Double-entry ledger** logic and **Idempotent state transitions**.

### **2. `payment-application` (Orchestration & Ports)**
- **Role**: Implements Use Cases, coordinates business flows, and defines Ports.
- **Components**: Inbound Ports (Use Cases), Outbound Ports (Database/Kafka interfaces), and Core Domain Services.
- **Logic**: Manages internal fund distribution, platform fees, and retry policies for PSP operations.

### **3. `common-db` (Shared Database Infrastructure)**
- **Role**: Reusable MyBatis utilities, JSON typehandlers, and base configuration templates.
- **Traits**: Provides `mybatis-config-template.xml` and standard JSONb handling across the platform.

### **4. `common-kafka` (Shared Messaging Infrastructure)**
- **Role**: Reusable Kafka utilities, generic event envelopes, and Jackson ser/deser configurations.
- **Traits**: Ensures type safety and consistent payload formatting for all Kafka producers and consumers.

### **5. `payment-infrastructure` (Shared Technical Adapters)**
- **Role**: General utility implementations like the Snowflake ID Generator.
- **Traits**: Contains only truly common infrastructure utilities, completely decoupled from database entities or Kafka topics.

### **4. `payment-service` (API & Edge Cell Inbound Adapter)**
- **Role**: API gateway only talks to external PSP, and save result to local db, and return reesult to shopper in for the Edge Cell Pod.
- **REST API**: Spring Web MVC controllers exposed to internal checkout/order services for synchronous payments and intents.
- **Ports & Adapters**: Implements local database storage using `LocalOutboxWriterPort` to guarantee transaction safety.
- **Wiring**: Manages local Edge Cell lifecycle, thread pools, and local database connection.

### **5. `payment-edge-workers` (Standalone Outbox Forwarder)**
- **Role**: Background worker that bridges the Edge Cell to the Central Node.
- **Outbox Forwarding**: Runs `LocalOutboxForwarderJob` asynchronously to claim local outbox events using `LocalOutboxEdgePort` and forward them to the Central consolidated DB using `CentralOutboxEdgePort`.
- **Fault Isolation**: Deployed in its own isolated Pod on the same edge node. If the worker encounters issues, crashes, or requires maintenance, it can be restarted by Kubernetes without taking down the entire synchronous Web API and local database.

### **6. `payment-central-relay` (Central Outbox Publisher)**
- **Role**: Centralized high-performance scheduler that publishes events to Kafka.
- **Resilient Relaying**: Hosts the global `OutboxRelayJob` which queries eligible events from the Central DB outbox using `CentralOutboxRelayPort` and a safe watermark (`T_Safe`).
- **Kafka Publishing**: Uses an isolated thread pool and `PaymentEventPublisher` to publish events strictly in-order to Kafka with guaranteed at-least-once delivery.

### **7. `payment-consumers` (Asynchronous Workers & Ledger Processors)**
- **Role**: Central asynchronous consumer engine.
- **Kafka Listeners**: Hosts all `@KafkaListener` components for capture, refund, PSP results, ledger recording, and balance updates.
- **Workloads**: Coordinates heavy asynchronous tasks like calling external PSP Gateways and executing double-entry ledger bookkeeping.


## 🟦 Outbox Pattern Implementation (Two-Stage Edge-to-Central)

The system uses a **Two-Stage Transactional Outbox Pattern** to ensure reliable event publishing from distributed stateless edge nodes to a highly available central relay, which ultimately publishes to Kafka.

### **Stage 1: Edge Node (The Edge Cell Topology)**

The Edge layer is responsible for synchronous payment acceptance (Stripe integration, intent creation) and local outbox creation. To achieve low-latency communication and perfectly linear horizontal scaling, the Edge layer components are scheduled together using strict **Kubernetes Node Affinity**, but deployed as **separate Pods** for lifecycle fault isolation.

**The Edge Components (Strict Co-location Ratio):**
A single Edge Cell ecosystem runs on an isolated `edgepool` node and consists of separate Pods communicating over the internal cluster network:
1. **`payment-service and local-edge-db lives in the same pod, local-edge-db- designed as initcointeinr restartpolicy=always` Pod (Web API)**: Handles high-throughput synchronous checkouts and creates `OutboxEvent` record persist local edge dbb
3. **`payment-edge-workers` Pod (Forwarder)**:  Background worker running in its own standalone Pod that polls the `edge-db` for `NEW` outbox events and pushes them to the **Central DB**.

**Fault Tolerance Hardening:**
- **Lifecycle Fault Isolation**: Previously, the worker and API shared the same Pod (the Sidecar pattern). However, if the worker crashed or required a restart, Kubernetes would forcefully terminate the entire Pod, bringing down the healthy Web API and Database with it. By separating them into independent Pods, a worker maintenance cycle or crash has zero impact on the synchronous Web API's uptime.
- **Co-Located Scheduling**: Even though they are separate Pods, strict `nodeSelector` rules force them to land on the exact same physical `edgepool` Virtual Machine, minimizing network latency while maximizing fault isolation.
- **Topology Spread Constraints**: The Edge Cells are mathematically forced to spread evenly across Cloud Availability Zones to survive datacenter outages.

### **Stage 2: Central Node (payment-central-relay & payment-consumers)**

The Central layer acts as the global system of record, ledger orchestrator, and Kafka publisher/consumer. It is divided into two highly available, autonomous modules to preserve separate scaling and thread/resource isolation boundaries:

- **`payment-central-relay`**: A dedicated, non-blocking service containing the global `OutboxRelayJob` and the `PaymentEventPublisher`. It continuously polls the Central DB's `outbox_event` table based on a globally safe `T_Safe` watermark (derived from all edge nodes) and publishes outbox events strictly in-order to Kafka using an isolated `resilientExecutor` thread pool.
- **`payment-consumers`**: Purely asynchronous consumer application containing all Kafka `@KafkaListener` event handlers. It consumes event streams from Kafka topics, handles PSP capture/refund execution, manages terminal result updates, and coordinates double-entry ledger bookkeeping and Redis balance-cache updates.

**Host Deployment, Fault Isolation, and Non-Sidecar Pattern:**
Unlike the Edge Cell which strictly enforces the Kubernetes Sidecar pattern (forcing the API, the local PostgreSQL database, and the local edge worker to reside in the same Pod to share localhost-based low-latency networking and co-located physical disk storage), **the Central Cluster components do NOT apply the sidecar pattern**. 

Since this represents an asynchronous processing path, **`payment-central-relay` and `payment-consumers` must NOT be co-located in the same Pod or physical host node**. Instead, they are completely decoupled asynchronously via Kafka to maximize durability and high availability:
- **Fault Domain Isolation**: If `payment-central-relay` (the outbox relay job) crashes or experiences an outage, `payment-consumers` remains fully operational. It continues to process, execute, and settle any backlog of payment events already stored in the Kafka cluster without interruption.
- **Resource Independence**: If the consumer layer experiences high latency due to slow external PSP gateway responses or intensive batch ledger writes, it will not steal CPU resources, memory, or DB connections from `payment-central-relay`, preventing cascading failures.
- **Anti-Affinity Scheduling**: Kubernetes deployments utilize **Pod Anti-Affinity rules** to physically separate `payment-central-relay` and `payment-consumers` onto different physical compute nodes and Availability Zones.

**Why This Topology:**
- **High Availability**: Edge cells can continue accepting payments and writing to their local Postgres databases even if the Central DB or Kafka goes down entirely.
- **Resource & Fault Isolation**: The outbox publishing scheduler run-loop is isolated in `payment-central-relay` with its own thread pool, ensuring that heavy consumer processing (e.g. slow PSP gateway calls or batch ledger updates in `payment-consumers`) can never block or exhaust the outbox publishing thread allocation.
- **Independent Scaling & Topology Separation**: Edge Cells can be scaled out linearly to handle localized high checkout volumes. Meanwhile, the central `payment-consumers` and `payment-central-relay` scale independently on separate compute hosts to handle global asynchronous workloads without constraints on co-location.
- **Guaranteed At-Least-Once Delivery**: Events are durably stored in the local outboxes first, forwarded to the central consolidated outbox, and only marked as dispatched upon a successful Kafka ack.

### **Stage 3: Outbox Port Architecture & Flow Control**

To maintain a strict **Hexagonal (Ports & Adapters)** design and prevent architectural pollution, outbox capabilities are split into four highly specialized outbound ports with clean, distinct responsibilities:

1. **`LocalOutboxWriterPort`**:
   - **Declared in**: `payment-application` / `ports/outbound`
   - **Used by**: `payment-service` (Web API)
   - **Responsibility**: Invoked within the local Edge transaction boundary to write `OutboxEvent` records directly into the local postgres database (`local-edge-db`).
2. **`LocalOutboxEdgePort`**:
   - **Declared in**: `payment-application` / `ports/outbound`
   - **Used by**: `payment-edge-workers` (Local Sidecar Forwarder)
   - **Responsibility**: Reads, claims, and marks local outbox events as dispatched. Declares specific methods such as `findEligible(batchSize, workerId)` and `markDispatched(events)`.
3. **`CentralOutboxEdgePort`**:
   - **Declared in**: `payment-application` / `ports/outbound`
   - **Used by**: `payment-edge-workers` (Local Sidecar Forwarder)
   - **Responsibility**: Acts as a bridge between edge node container and the consolidated Central DB cluster. Invoked by the local forwarder to insert batches of claimed edge events (`insertBatch(edgeNodeId, entries)`) into the central `outbox_event` table.
4. **`CentralOutboxRelayPort`**:
   - **Declared in**: `payment-application` / `ports/outbound`
   - **Used by**: `payment-central-relay` (Central Outbox Publisher)
   - **Responsibility**: Provides the read/write API for the central consolidated outbox table. Exposes `findEligible(tSafe, batchSize)` to query unclaimed events safely behind the watermark `T_Safe`, and `markDispatched(oeid)` to finalize publication upon successful Kafka broker acknowledgment.

### **Stage 4: Database Connection URLs & Role-Based Credentials**

In line with strict security and network isolation principles, **there is no shared database configuration or connection account**. Each runtime component is allocated a dedicated PostgreSQL user role with the minimum privileges required to perform its specific task.

#### **1. Edge Database Access (Local Edge Cell)**
- **Scope**: Local transactions, high throughput, low latency.
- **Config Variable**: `EDGE_DB_URL`
- **JDBC Connection URL**: `jdbc:postgresql://localhost:5432/edge-db?options=-c%20timezone=UTC`
- **Component Credentials**:
  * **`payment-service`**:
    * **Username Key**: `EDGE_DB_PAYMENT_SERVICE_USERNAME`
    * **Username**: `edge_db_payment_service_username`
  * **`payment-edge-workers`**:
    * **Username Key**: `EDGE_DB_PAYMENT_EDGE_WORKERS_USERNAME`
    * **Username**: `edge_db_payment_edge_workers_username`

#### **2. Central Database Access (Global Consolidated State)**
- **Scope**: Multi-seller consolidated outbox, double-entry ledger bookkeeping, and global account balance snapshots.
- **Config Variable**: `CENTRAL_DB_URL` (mapped internally to `SPRING_DATASOURCE_URL` or resolved locally via `SPRING_DATASOURCE_CENTRAL_URL`).
- **JDBC Connection URLs**:
  - **Kubernetes / Containerized Production**:
    `jdbc:postgresql://central-db-postgresql:5432/central-db?options=-c%20timezone=UTC`
  - **Local Development Environment**:
    `jdbc:postgresql://localhost:5432/central-db?options=-c%20timezone=UTC`
- **Component Credentials**:
  * **`payment-consumers`**:
    * **Username Key**: `CENTRAL_DB_PAYMENT_CONSUMERS_USERNAME`
    * **Username**: `central_db_payment_consumers_username`
  * **`payment-edge-workers`** (when writing to central outbox):
    * **Username Key**: `CENTRAL_DB_PAYMENT_EDGE_WORKERS_USERNAME`
    * **Username**: `central_db_payment_edge_workers_username`
  * **`payment-central-relay`** (when relaying central outbox to Kafka):
    * **Username Key**: `CENTRAL_DB_PAYMENT_CENTRAL_RELAY_USERNAME`
    * **Username**: `central_db_payment_central_relay_username`

---

## 🟦 Kafka Event Typology & Type Verification

To satisfy strict financial auditability and message correctness (NFR2/NFR6), the platform implements **strict compile-time type-safety** and **declarative runtime serialization**. 

### 1. The Kafka Event and Command Topology

The following catalog defines every event and command passing through Kafka, including their exact topic mappings, event type strings, payload envelope classes, publishers, and consumers:

| No. | Logical Event / Command | Event Type String (`eventType`) | Envelope Payload Class | Kafka Topic | Publisher Module & Class | Consumer Module & Class | Consumer Group ID | Container Factory Bean |
|---|---|---|---|---|---|---|---|---|
| **1** | **Payment Authorized Event** | `"payment_authorized"` | `EventEnvelope<PaymentAuthorized>` | `payment.psp.results` | `payment-central-relay`<br/>`OutboxRelayJob` | `payment-consumers`<br/>`PspResultConsumer` | `payment-core.psp-result-consumer` | `payment-core.psp-result-consumer-factory` |
| **2** | **Capture Requested Event** | `"capture_requested"` | `EventEnvelope<CaptureRequested>` | `gateway.capture.commands` | `payment-central-relay`<br/>`OutboxRelayJob` | `payment-consumers`<br/>`CaptureCommandExecutor` | `gateway-workers.capture-command-executor` | `gateway-workers.capture-command-executor-factory` |
| **3** | **Capture Submitted Event** | `"capture_submitted"` | `EventEnvelope<CaptureSubmitted>` | `gateway.capture.submitted` | `payment-central-relay`<br/>`OutboxRelayJob` | `payment-consumers`<br/>`CapturePspPerformedConsumer` | `payment-core.capture-submitted` | `payment-core.capture-submitted-factory` |
| **4** | **Capture Confirmed Event** | `"capture_confirmed"` | `EventEnvelope<CaptureConfirmed>` | `payment.psp.results` | `payment-central-relay`<br/>`OutboxRelayJob` | `payment-consumers`<br/>`PspResultConsumer` | `payment-core.psp-result-consumer` | `payment-core.psp-result-consumer-factory` |
| **5** | **Internal Transfer Command** | `"internal_transfer_command"` | `EventEnvelope<InternalTransferCommand>` | `payment.psp.results` | `payment-central-relay`<br/>`OutboxRelayJob` | `payment-consumers`<br/>`PspResultConsumer` | `payment-core.psp-result-consumer` | `payment-core.psp-result-consumer-factory` |
| **6** | **Journal Entries Recorded** | `"journal_entries_recorded"` | `EventEnvelope<JournalEntriesRecorded>` | `journal.entries.recorded` | `payment-consumers`<br/>`ProcessPspResultUseCase` | `payment-consumers`<br/>`GrossCaptureAllocationConsumer`<br/>`AccountBalanceConsumer`<br/>`SimulatedSdrStreamingProcessorConsumer` | `payment.gross-capture-allocation-consumer-group`<br/>`ledger-engine.account-balance-consumer`<br/>`ledger-engine.simulated-settlement-consumer` | `...-factory` |
| **7** | **Settlement Received Event** | `"settlement_received_by_psp"` | `EventEnvelope<SettlementReceived>` | `payment.psp.results` | `payment-central-relay`<br/>`OutboxRelayJob` | `payment-consumers`<br/>`PspResultConsumer` | `payment-core.psp-result-consumer` | `payment-core.psp-result-consumer-factory` |

---

### 2. Strict Type Safety & Generics Preservation

#### Avoidance of Type Erasure
In early iterations, generic event envelopes were sometimes cast to a raw `EventEnvelope<Event>` base wrapper. This degraded runtime type signatures and stripped Jackson of the concrete metadata needed to map and deserialize nested JSON sub-structures correctly. 

To harden this, the system strictly implements **concrete type preservation** across both publication and consumption:
1. **At Publication (`OutboxRelayJob` & `PaymentEventPublisher`)**:
   Instead of calling `publishBatchAtomically<Event>`, the relay job parses the internal outbox event type and casts the envelope to its exact, concrete compile-time generic class (e.g. `EventEnvelope<PaymentAuthorized>`, `EventEnvelope<CaptureReceived>`, or `EventEnvelope<RefundReceived>`).
2. **At Serialization (`JacksonSerializationAdapter` & `EventEnvelopeKafkaSerializer`)**:
   The serialization adapter preserves the complete generic structure, appending the event's fully-qualified class details and type metadata into the JSON payload and Kafka headers (e.g., `traceId`, `eventId`, `eventType`).

#### Runtime Deserialization Binding
Kafka messages are consumed using Spring Kafka's `ErrorHandlingDeserializer` delegating to our custom `EventEnvelopeKafkaDeserializer`. 
- **The Metadata Catalog (`PaymentEventMetadataCatalog`)**:
  Maintains a registry mapping each event class to a specific `TypeReference<EventEnvelope<T>>`.
- **Deserializer Resolution**:
  When a byte array is pulled from a topic, the deserializer resolves the topic's corresponding `TypeReference` from the catalog and calls `objectMapper.readValue(data, typeRef)`. This forces Jackson to reconstruct the exact nested type (e.g. `CaptureReceived`) instead of falling back to a raw map or base class.
- **Type Filtering**:
  At the container listener level (`KafkaTypedConsumerFactoryConfig`), the container factory is registered with a `RecordFilterStrategy` matching the class's exact expected event type. This ensures that any malformed or unexpected events are filtered or routed to the DLQ immediately without crashing the consumer.

