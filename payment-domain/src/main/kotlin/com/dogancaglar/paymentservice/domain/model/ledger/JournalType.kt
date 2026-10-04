package com.dogancaglar.paymentservice.domain.model.ledger

enum class JournalType {
    AUTHORIZATION, // DR AUTH_RECEIVABLE / CR AUTH_LIABILITY (Hold position)
    CAPTURE, // DR AUTH_LIABILITY / CR AUTH_RECEIVABLE & DR PSP_RECEIVABLE / CR CAPTURE_SUSPENSE
    INTERNAL_TRANSFER, // DR CAPTURE_SUSPENSE / CR SELLER_PAYABLE or MERCHANT_*_PAYABLE
    REFUND, // Reversal of capture balances due to customer return
    SETTLEMENT, // represent a settlement of  single transaction which was sent with other trasnactions in same fileMulti-payment batch processing from PSP file (clears receivables into platform bank)
    PSP_FEE, // this should represent thefee that we pay to our actual psp adyen or stripe,expense for us ,a nd also it should be in PSP_FEE_EXPENSE account i guess
    COMMISSION_FEE, // DR MERCHANT_*_PAYABLE / CR PLATFORM_FEE_RESERVE(tmp for us) this is what we charge our merchants,not moved to oour real account, just temp place for refund guarantee i guess
    REVENUE_RECOGNITION, // this is movong moneey from probably  our earlier tmp PLATFORM_FEE_RESERVE to  to our real account
    PAYOUT, // DR MERCHANT or SELLER balance / CR PLATFORM_CASH (Physical transfer out)
    ADJUSTMENT // Handling settlement discrepancies
}
