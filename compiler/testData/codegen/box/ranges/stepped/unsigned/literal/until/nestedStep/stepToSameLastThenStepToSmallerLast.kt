// Auto-generated by GenerateSteppedRangesCodegenTestData. Do not edit!
// WITH_STDLIB
import kotlin.test.*

fun box(): String {
    val uintList = mutableListOf<UInt>()
    for (i in 1u until 11u step 3 step 2) {
        uintList += i
    }
    assertEquals(listOf(1u, 3u, 5u, 7u, 9u), uintList)

    val ulongList = mutableListOf<ULong>()
    for (i in 1uL until 11uL step 3L step 2L) {
        ulongList += i
    }
    assertEquals(listOf(1uL, 3uL, 5uL, 7uL, 9uL), ulongList)

    return "OK"
}