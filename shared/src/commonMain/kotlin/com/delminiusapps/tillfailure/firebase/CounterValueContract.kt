package com.delminiusapps.tillfailure.firebase

/**
 * Raised when a stored counter value cannot be safely incremented. Both platform adapters map
 * this to the stable, non-retryable `INVALID_ARGUMENT` failure instead of overwriting,
 * truncating, or silently resetting the stored value.
 */
class CounterValueException(message: String) : IllegalStateException(message)

/**
 * Canonical contract for incrementable Firestore counter values shared by the Android adapter
 * and the Apple bridge.
 *
 * Counters are stored as integers. Externally written string values are accepted only when
 * they are a canonical signed 64-bit integer representation (`-?[0-9]+`: no whitespace, no
 * plus sign, no decimal point, no exponent, and within `Long` range). A missing or explicitly
 * null field starts from zero, matching the documented adapter behavior. Every other stored
 * representation (booleans, floating point, arrays, maps, timestamps, references, malformed
 * strings) is rejected so a transaction never silently resets or truncates corrupt data.
 */
object CounterValueContract {
    /** Returns the parsed value, or `null` when [text] is not a canonical in-range integer. */
    fun parseCanonicalInt64(text: String): Long? {
        if (text.isEmpty()) return null
        var index = 0
        val negative = text[0] == '-'
        if (negative) {
            index = 1
            if (text.length == 1) return null
        }
        var magnitude = 0L
        while (index < text.length) {
            val character = text[index]
            if (character < '0' || character > '9') return null
            val digit = character - '0'
            if (magnitude < (Long.MIN_VALUE + digit) / 10) return null
            magnitude = magnitude * 10 - digit
            index += 1
        }
        if (!negative && magnitude == Long.MIN_VALUE) return null
        return if (negative) magnitude else -magnitude
    }

    /** Returns the exact sum, or `null` when the addition would overflow `Long`. */
    fun addExact(left: Long, right: Long): Long? {
        val result = left + right
        if (((left xor result) and (right xor result)) < 0) return null
        return result
    }
}
