package com.kaoyan.wordhelper.data.repository

import com.kaoyan.wordhelper.data.entity.WordMemory
import com.kaoyan.wordhelper.data.model.CardSeedLoader
import java.security.MessageDigest

internal object SeedMemoryUpgrade {
    fun previousFingerprints(version: Int, bookName: String): Map<String, String> =
        if (version == 3 && bookName == CardSeedLoader.DEFAULT_BOOK_NAME) cet6V2Fingerprints else emptyMap()

    fun canReplace(
        existing: WordMemory,
        incomingSource: String,
        previousFingerprint: String?,
        externalSeedOverride: Boolean
    ): Boolean {
        if (existing.rev != 0 || !existing.prevApproach.isNullOrBlank() ||
            !existing.prevMethod.isNullOrBlank() || existing.unverified
        ) return false
        return when (incomingSource) {
            WordMemory.SOURCE_IMPORT ->
                existing.source == WordMemory.SOURCE_SEED || existing.source == WordMemory.SOURCE_IMPORT
            WordMemory.SOURCE_SEED -> existing.source == WordMemory.SOURCE_SEED && (
                externalSeedOverride ||
                    (existing.approach.isBlank() && existing.collocations.isBlank()) ||
                    (previousFingerprint != null && fingerprint(existing) == previousFingerprint)
                )
            else -> false
        }
    }

    private fun fingerprint(memory: WordMemory): String = MessageDigest.getInstance("SHA-256")
        .digest("${memory.approach}\u0000${memory.collocations}".toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    // 1.4.7.1 bundled v2: SHA-256 of approach + NUL + collocations after CardSeedLoader.toDrafts.
    // Old file imports also used source=seed, so a source label alone cannot authorize replacement.
    private val cet6V2Fingerprints = mapOf(
        "adolescent" to "abdfa84bcbe697632e6158dc7e37188fc56ff957a75c0d8bad31ca2634fb7f12",
        "technology" to "322e1634e469cbbb5528452a5f35e913980d96e298fe41e1b2e1d9fc2268403f",
        "experience" to "4b5c9501f06ac0ca6994610d289a50863ca54575ebdc5ffc7480943d06811f1e",
        "mental" to "585af8f02c44cc4f7ad724d1860912d4594f090f5d40fc25e919112482665f80",
        "affect" to "6156012be0b7dc886c83fbe4c75a1c2c30c0f779d8a78f04418eb5a93a1e60d1",
        "enhance" to "b74963394ef6928dadf2d7152fa35e05d70aec7b52aae4979d79d2b98c235ed4",
        "population" to "11e8f1be6d5166034e6a8cad846777f6939ca0fc430c0909e456b507e35b086c",
        "implement" to "29a07e8cac52412e65057e46bdb32fd5505e08d95610e437c10635995f8026f1",
        "cathedral" to "079139110a3f95dc849281c5ea4e0950c30dc8cf55dfbc08a0a33080af545073",
        "stereotype" to "a085d4ea02ac576b9215f6147a8a2428c49fdc277ddc074373a1bea13f04a9a4",
        "economic" to "35a2c74f7c2f2eb2991716ffdaf74826eebb265029ed13f9cc3d5778af876f30",
        "endeavor" to "274370d0230c3ef96373bca358ef7589b130e34a90b4e93a5f8115e1db870efa",
        "profile" to "aff5757642faacbd0ab911bd33b0e6b2dccdc9893ecf04dd7ab32d8a35ab1d46",
        "pyramid" to "32595cfaf344c4a12dba4655986e62672506f2fedac43254e1c434bfa8bcb873",
        "conceive" to "261e107f43a31419402289365ae63ade8cbc3f28b1dd3d736f4c5b85871b09a2",
        "effective" to "5231ebfa85ded64b4ef4faf27cd3f93013213c5f0945e9b609012337c7d8adc1",
        "intellect" to "fdab368efc506f23b3463454003f38aa37e36e43c0c76bfadbbeb1c5aebe7ba6"
    )
}
