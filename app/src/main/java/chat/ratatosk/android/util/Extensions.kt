package chat.ratatosk.android.util

fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

fun String.hexToByteArray(): ByteArray {
    check(length % 2 == 0) { "Must have an even length" }
    return chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}
