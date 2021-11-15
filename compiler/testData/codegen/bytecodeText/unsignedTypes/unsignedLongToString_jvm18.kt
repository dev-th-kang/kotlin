// JVM_TARGET: 1.8
// WITH_STDLIB

fun box(): String {
    val min = 0UL.toString()
    if ("0" != min) throw AssertionError(min)

    val middle = 9_223_372_036_854_775_807UL.toString()
    if ("9223372036854775807" != middle) throw AssertionError(middle)

    val max = 18_446_744_073_709_551_615UL.toString()
    if ("18446744073709551615" != max) throw AssertionError(max)

    return "OK"
}

// 0 kotlin/ULong.toString
// 3 INVOKESTATIC java/lang/Long.toUnsignedString \(J\)Ljava/lang/String;
