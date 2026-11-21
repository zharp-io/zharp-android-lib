package io.zharp.sample.monitoring

/** What the Crash button runs: a cart line without a quantity, read as if it had one. */
class Cart(private val lines: List<Line>) {
    data class Line(val cents: Int, val quantity: Int?)

    fun total(): Int {
        var total = 0
        for (line in lines) {
            total += line.cents * line.quantity!!
        }
        return total
    }
}
