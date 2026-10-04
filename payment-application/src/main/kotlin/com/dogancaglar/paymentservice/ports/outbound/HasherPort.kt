package com.dogancaglar.paymentservice.ports.outbound

interface HasherPort {

    fun hashBody(body: Any): String
}
