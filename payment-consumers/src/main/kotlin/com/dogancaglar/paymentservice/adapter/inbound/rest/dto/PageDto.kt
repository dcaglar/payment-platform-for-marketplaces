package com.dogancaglar.paymentservice.adapter.inbound.rest.dto

/** One page of a list, newest or first items first. [page] starts at 0. */
data class PageDto<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int,
    val hasNext: Boolean,
    val hasPrevious: Boolean
) {
    companion object {
        fun <T> of(items: List<T>, page: Int, size: Int, totalItems: Long): PageDto<T> {
            val totalPages = ((totalItems + size - 1) / size).toInt()
            return PageDto(
                items = items,
                page = page,
                size = size,
                totalItems = totalItems,
                totalPages = totalPages,
                hasNext = (page + 1) < totalPages,
                hasPrevious = page > 0
            )
        }
    }
}
