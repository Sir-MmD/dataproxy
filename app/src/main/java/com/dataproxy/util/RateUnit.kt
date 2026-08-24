package com.dataproxy.util

/**
 * User preference for how live transfer rates are displayed.
 *
 * [BytesPerSecond] is the historical binary (÷1024) B/s ladder.
 * [Mbps] is the decimal (÷1000) bits-per-second ladder networking tools
 * conventionally use for speed. The two are not simple scalar multiples of
 * each other. Cycled by tapping the unit label on the Speed card.
 */
enum class RateUnit(val key: String) {
    BytesPerSecond("bytes"),
    Mbps("mbps");

    companion object {
        fun fromKey(key: String?): RateUnit =
            entries.firstOrNull { it.key == key } ?: BytesPerSecond
    }
}
