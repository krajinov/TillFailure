package com.delminiusapps.tillfailure.firebase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CounterValueContractTest {
    @Test
    fun parsesCanonicalSignedIntegerStrings() {
        assertEquals(0L, CounterValueContract.parseCanonicalInt64("0"))
        assertEquals(5L, CounterValueContract.parseCanonicalInt64("5"))
        assertEquals(-7L, CounterValueContract.parseCanonicalInt64("-7"))
        assertEquals(7L, CounterValueContract.parseCanonicalInt64("007"))
        assertEquals(Long.MAX_VALUE, CounterValueContract.parseCanonicalInt64("9223372036854775807"))
        assertEquals(Long.MIN_VALUE, CounterValueContract.parseCanonicalInt64("-9223372036854775808"))
    }

    @Test
    fun rejectsMalformedAmbiguousAndOutOfRangeStrings() {
        assertNull(CounterValueContract.parseCanonicalInt64(""))
        assertNull(CounterValueContract.parseCanonicalInt64("-"))
        assertNull(CounterValueContract.parseCanonicalInt64("+5"))
        assertNull(CounterValueContract.parseCanonicalInt64(" 5"))
        assertNull(CounterValueContract.parseCanonicalInt64("5 "))
        assertNull(CounterValueContract.parseCanonicalInt64("5.0"))
        assertNull(CounterValueContract.parseCanonicalInt64("5e3"))
        assertNull(CounterValueContract.parseCanonicalInt64("five"))
        assertNull(CounterValueContract.parseCanonicalInt64("1,000"))
        assertNull(CounterValueContract.parseCanonicalInt64("9223372036854775808"))
        assertNull(CounterValueContract.parseCanonicalInt64("-9223372036854775809"))
    }

    @Test
    fun addsExactValuesAndDetectsOverflow() {
        assertEquals(6L, CounterValueContract.addExact(5L, 1L))
        assertEquals(1L, CounterValueContract.addExact(0L, 1L))
        assertEquals(-2L, CounterValueContract.addExact(-3L, 1L))
        assertEquals(Long.MAX_VALUE, CounterValueContract.addExact(Long.MAX_VALUE, 0L))
        assertNull(CounterValueContract.addExact(Long.MAX_VALUE, 1L))
        assertNull(CounterValueContract.addExact(Long.MIN_VALUE, -1L))
        assertNull(CounterValueContract.addExact(Long.MIN_VALUE, Long.MIN_VALUE))
    }
}
