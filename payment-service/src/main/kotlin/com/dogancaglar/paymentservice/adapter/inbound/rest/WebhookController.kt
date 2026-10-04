package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.stripe.exception.SignatureVerificationException
import com.stripe.net.Webhook
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class WebhookController(
    private val paymentApiOrchestrator: PaymentApiOrchestrator,
    @Value("\${stripe.webhook-secret:}") private val endpointSecret: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @PostMapping("/webhook")
    fun handleWebhook(
        @RequestBody payload: String,
        @RequestHeader("Stripe-Signature") sigHeader: String?
    ): ResponseEntity<String> {
        if (endpointSecret.isBlank()) {
            logger.warn("Stripe webhook secret is not configured. Ignoring webhook.")
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Webhook secret not configured")
        }

        if (sigHeader == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Missing Stripe-Signature header")
        }

        return try {
            val event = Webhook.constructEvent(payload, sigHeader, endpointSecret)
            paymentApiOrchestrator.processWebhook(event)
            ResponseEntity.ok("")
        } catch (e: SignatureVerificationException) {
            logger.warn("Invalid Stripe signature: {}", e.message)
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid signature")
        } catch (e: Exception) {
            logger.error("Webhook processing error", e)
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Webhook error")
        }
    }
}
