package com.dataproxy.network

enum class NetworkMode(val key: String) {
    CELLULAR("cellular"),
    WIFI("wifi"),
    AUTO("auto");

    companion object {
        fun fromKey(key: String?): NetworkMode =
            entries.firstOrNull { it.key == key } ?: CELLULAR
    }
}
