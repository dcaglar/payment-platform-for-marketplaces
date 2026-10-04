package com.dogancaglar.paymentservice.adapter.inbound.rest

import com.dogancaglar.port.out.web.dto.CreateAccountRequestDTO
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Account onboarding (base URL .../api/v1). Creation is asynchronous: the request is validated and queued,
 * and the accounts are created shortly after. The answer is only "accepted".
 */
@RestController
@RequestMapping("/api/v1")
class AccountController(
    private val accountApiOrchestrator: AccountApiOrchestrator
) {
    @PreAuthorize("hasAuthority('account:write')")
    @PostMapping("/accounts")
    fun createAccount(@Valid @RequestBody request: CreateAccountRequestDTO): ResponseEntity<Map<String, String>> {
        accountApiOrchestrator.requestCreation(request)
        val body = LinkedHashMap<String, String>()
        body["merchantAccountCode"] = request.merchantAccountCode
        body["status"] = "ACCEPTED"
        body["message"] = "Request accepted, will be processed soon"
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body)
    }
}
