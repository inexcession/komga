package org.gotson.komga.interfaces.api.rest.dto

/**
 * An item of a grouped series listing.
 * Exactly one of [series] or [collection] is set.
 */
data class SeriesGroupedItemDto(
  val series: SeriesDto? = null,
  val collection: CollectionDto? = null,
)

fun SeriesGroupedItemDto.restrictUrl(restrict: Boolean) = copy(series = series?.restrictUrl(restrict))
