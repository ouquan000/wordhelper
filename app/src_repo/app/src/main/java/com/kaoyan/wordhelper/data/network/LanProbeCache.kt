package com.kaoyan.wordhelper.data.network

/** Successful identities stay warm; a missed broadcast only throttles immediate retries. */
internal class LanProbeCache<T> {
    data class Entry<T>(val key: String, val value: T?, val expiresAt: Long)

    @Volatile private var last: Entry<T>? = null

    fun lookup(key: String, now: Long): Entry<T>? =
        last?.takeIf { it.key == key && now < it.expiresAt }

    fun record(key: String, value: T?, now: Long) {
        val ttl = if (value == null) 2_000L else 30_000L
        last = Entry(key, value, now + ttl)
    }
}
