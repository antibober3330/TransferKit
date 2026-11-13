package com.transferkit.client.protocol

/** Unicode code-point ordering shared by all Android canonical JSON paths. */
internal val JSON_CODE_POINT_ORDER = Comparator<String> { first, second ->
    var firstIndex = 0
    var secondIndex = 0
    while (firstIndex < first.length && secondIndex < second.length) {
        val firstPoint = first.codePointAt(firstIndex)
        val secondPoint = second.codePointAt(secondIndex)
        if (firstPoint != secondPoint) return@Comparator firstPoint.compareTo(secondPoint)
        firstIndex += Character.charCount(firstPoint)
        secondIndex += Character.charCount(secondPoint)
    }
    (first.length - firstIndex).compareTo(second.length - secondIndex)
}
