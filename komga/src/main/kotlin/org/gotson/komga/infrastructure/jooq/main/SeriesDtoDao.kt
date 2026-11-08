package org.gotson.komga.infrastructure.jooq.main

import org.gotson.komga.application.query.isEmpty
import org.gotson.komga.domain.model.SearchContext
import org.gotson.komga.domain.model.SearchField
import org.gotson.komga.domain.model.SeriesSearch
import org.gotson.komga.domain.persistence.SeriesCollectionRepository
import org.gotson.komga.infrastructure.jooq.RequiredJoin
import org.gotson.komga.infrastructure.jooq.SeriesSearchHelper
import org.gotson.komga.infrastructure.jooq.SplitDslDaoBase
import org.gotson.komga.infrastructure.jooq.TempTable.Companion.withTempTable
import org.gotson.komga.infrastructure.jooq.csAlias
import org.gotson.komga.infrastructure.jooq.inOrNoCondition
import org.gotson.komga.infrastructure.jooq.sortByValues
import org.gotson.komga.infrastructure.jooq.toSortField
import org.gotson.komga.infrastructure.jooq.unicode3
import org.gotson.komga.infrastructure.search.LuceneEntity
import org.gotson.komga.infrastructure.search.LuceneHelper
import org.gotson.komga.infrastructure.web.toFilePath
import org.gotson.komga.interfaces.api.persistence.SeriesDtoRepository
import org.gotson.komga.interfaces.api.rest.dto.AlternateTitleDto
import org.gotson.komga.interfaces.api.rest.dto.AuthorDto
import org.gotson.komga.interfaces.api.rest.dto.BookMetadataAggregationDto
import org.gotson.komga.interfaces.api.rest.dto.GroupCountDto
import org.gotson.komga.interfaces.api.rest.dto.NotableEntityDto
import org.gotson.komga.interfaces.api.rest.dto.SeriesDto
import org.gotson.komga.interfaces.api.rest.dto.SeriesGroupedItemDto
import org.gotson.komga.interfaces.api.rest.dto.SeriesMetadataDto
import org.gotson.komga.interfaces.api.rest.dto.WebLinkDto
import org.gotson.komga.interfaces.api.rest.dto.toDto
import org.gotson.komga.jooq.main.Tables
import org.gotson.komga.jooq.main.tables.records.BookMetadataAggregationRecord
import org.gotson.komga.jooq.main.tables.records.SeriesMetadataRecord
import org.gotson.komga.jooq.main.tables.records.SeriesRecord
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.ResultQuery
import org.jooq.SelectJoinStep
import org.jooq.SelectOnConditionStep
import org.jooq.impl.DSL
import org.jooq.impl.DSL.count
import org.jooq.impl.DSL.countDistinct
import org.jooq.impl.DSL.lower
import org.jooq.impl.DSL.substring
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime

const val BOOKS_UNREAD_COUNT = "booksUnreadCount"
const val BOOKS_IN_PROGRESS_COUNT = "booksInProgressCount"
const val BOOKS_READ_COUNT = "booksReadCount"

private const val KIND_SERIES = "SERIES"
private const val KIND_COLLECTION = "COLLECTION"
private const val G_KIND = "kind"
private const val G_ITEM_ID = "item_id"
private const val G_SERIES_ID = "series_id"
private const val G_TITLE_SORT = "title_sort"
private const val G_NAME = "name"
private const val G_CREATED_DATE = "created_date"
private const val G_LAST_MODIFIED_DATE = "last_modified_date"
private const val G_RELEASE_DATE = "release_date"
private const val G_READ_DATE = "read_date"
private const val G_BOOKS_COUNT = "books_count"

@Component
class SeriesDtoDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
  private val luceneHelper: LuceneHelper,
  private val collectionRepository: SeriesCollectionRepository,
  @param:Value("#{@komgaProperties.database.batchChunkSize}") private val batchSize: Int,
) : SplitDslDaoBase(dslRW, dslRO),
  SeriesDtoRepository {
  private val s = Tables.SERIES
  private val d = Tables.SERIES_METADATA
  private val rs = Tables.READ_PROGRESS_SERIES
  private val cs = Tables.COLLECTION_SERIES
  private val c = Tables.COLLECTION
  private val g = Tables.SERIES_METADATA_GENRE
  private val st = Tables.SERIES_METADATA_TAG
  private val sl = Tables.SERIES_METADATA_SHARING
  private val slk = Tables.SERIES_METADATA_LINK
  private val sat = Tables.SERIES_METADATA_ALTERNATE_TITLE
  private val bma = Tables.BOOK_METADATA_AGGREGATION
  private val bmaa = Tables.BOOK_METADATA_AGGREGATION_AUTHOR
  private val bmat = Tables.BOOK_METADATA_AGGREGATION_TAG
  private val bmane = Tables.BOOK_METADATA_AGGREGATION_NOTABLE_ENTITIES

  private val groupFields =
    arrayOf(
      *s.fields(),
      *d.fields(),
      *bma.fields(),
      *rs.fields(),
    )

  private val sorts =
    mapOf(
      "metadata.titleSort" to d.TITLE_SORT.unicode3(),
      "createdDate" to s.CREATED_DATE,
      "created" to s.CREATED_DATE,
      "lastModifiedDate" to s.LAST_MODIFIED_DATE,
      "lastModified" to s.LAST_MODIFIED_DATE,
      "booksMetadata.releaseDate" to bma.RELEASE_DATE,
      "readDate" to rs.MOST_RECENT_READ_DATE,
      "collection.number" to cs.NUMBER,
      "name" to s.NAME.unicode3(),
      "booksCount" to s.BOOK_COUNT,
      "random" to DSL.rand(),
    )

  override fun findAll(pageable: Pageable): Page<SeriesDto> = findAll(SeriesSearch(), SearchContext.ofAnonymousUser(), pageable)

  override fun findAll(
    context: SearchContext,
    pageable: Pageable,
  ): Page<SeriesDto> = findAll(SeriesSearch(), context, pageable)

  override fun findAll(
    search: SeriesSearch,
    context: SearchContext,
    pageable: Pageable,
  ): Page<SeriesDto> {
    requireNotNull(context.userId) { "Missing userId in search context" }

    val (conditions, joins) = SeriesSearchHelper(context).toCondition(search.condition)
    val conditionsRefined = conditions.and(search.regexSearch?.let { it.second.toColumn().likeRegex(it.first) } ?: DSL.noCondition())

    return findAll(conditionsRefined, context.userId, pageable, joins, search.fullTextSearch)
  }

  override fun findAllGrouped(
    search: SeriesSearch,
    context: SearchContext,
    pageable: Pageable,
  ): Page<SeriesGroupedItemDto> {
    requireNotNull(context.userId) { "Missing userId in search context" }

    val (conditions, joins) = SeriesSearchHelper(context).toCondition(search.condition)
    val conditionsRefined = conditions.and(search.regexSearch?.let { it.second.toColumn().likeRegex(it.first) } ?: DSL.noCondition())

    val seriesIds = luceneHelper.searchEntitiesIds(search.fullTextSearch, LuceneEntity.Series)
    val collectionIds = luceneHelper.searchEntitiesIds(search.fullTextSearch, LuceneEntity.Collection)

    // all the series matching the search conditions
    val matched =
      DSL
        .selectDistinct(
          s.ID.`as`(G_SERIES_ID),
          d.TITLE_SORT.`as`(G_TITLE_SORT),
          s.NAME.`as`(G_NAME),
          s.CREATED_DATE.`as`(G_CREATED_DATE),
          s.LAST_MODIFIED_DATE.`as`(G_LAST_MODIFIED_DATE),
          bma.RELEASE_DATE.`as`(G_RELEASE_DATE),
          rs.MOST_RECENT_READ_DATE.`as`(G_READ_DATE),
          s.BOOK_COUNT.`as`(G_BOOKS_COUNT),
        ).from(s)
        .leftJoin(d)
        .on(s.ID.eq(d.SERIES_ID))
        .leftJoin(bma)
        .on(s.ID.eq(bma.SERIES_ID))
        .leftJoin(rs)
        .on(s.ID.eq(rs.SERIES_ID))
        .and(readProgressConditionSeries(context.userId))
        .applyJoins(joins)
        .where(conditionsRefined)
        .asTable("matched_series")

    val mSeriesId = matched.field(G_SERIES_ID, String::class.java)!!
    val mReleaseDate = matched.field(G_RELEASE_DATE, LocalDate::class.java)!!
    val mReadDate = matched.field(G_READ_DATE, LocalDateTime::class.java)!!
    val mBooksCount = matched.field(G_BOOKS_COUNT, Int::class.java)!!

    // series that do not belong to any collection
    val standalone =
      DSL
        .select(
          DSL.inline(KIND_SERIES).`as`(G_KIND),
          mSeriesId.`as`(G_ITEM_ID),
          matched.field(G_TITLE_SORT, String::class.java)!!.`as`(G_TITLE_SORT),
          matched.field(G_NAME, String::class.java)!!.`as`(G_NAME),
          matched.field(G_CREATED_DATE, LocalDateTime::class.java)!!.`as`(G_CREATED_DATE),
          matched.field(G_LAST_MODIFIED_DATE, LocalDateTime::class.java)!!.`as`(G_LAST_MODIFIED_DATE),
          mReleaseDate.`as`(G_RELEASE_DATE),
          mReadDate.`as`(G_READ_DATE),
          mBooksCount.`as`(G_BOOKS_COUNT),
        ).from(matched)
        .where(mSeriesId.notIn(DSL.select(cs.SERIES_ID).from(cs)))
        .and(mSeriesId.inOrNoCondition(seriesIds))

    // collections containing at least one matching series
    val collectionSearchCondition =
      if (search.fullTextSearch.isNullOrBlank())
        DSL.noCondition()
      else
        mSeriesId.inOrNoCondition(seriesIds).or(c.ID.inOrNoCondition(collectionIds))

    val collections =
      DSL
        .select(
          DSL.inline(KIND_COLLECTION).`as`(G_KIND),
          c.ID.`as`(G_ITEM_ID),
          c.NAME.`as`(G_TITLE_SORT),
          c.NAME.`as`(G_NAME),
          c.CREATED_DATE.`as`(G_CREATED_DATE),
          c.LAST_MODIFIED_DATE.`as`(G_LAST_MODIFIED_DATE),
          DSL.min(mReleaseDate).`as`(G_RELEASE_DATE),
          DSL.max(mReadDate).`as`(G_READ_DATE),
          DSL.sum(mBooksCount).cast(Int::class.java).`as`(G_BOOKS_COUNT),
        ).from(matched)
        .join(cs)
        .on(cs.SERIES_ID.eq(mSeriesId))
        .join(c)
        .on(c.ID.eq(cs.COLLECTION_ID))
        .where(collectionSearchCondition)
        .groupBy(c.ID, c.NAME, c.CREATED_DATE, c.LAST_MODIFIED_DATE)

    val grouped = standalone.unionAll(collections).asTable("grouped_items")
    val gKind = grouped.field(G_KIND, String::class.java)!!
    val gItemId = grouped.field(G_ITEM_ID, String::class.java)!!

    val groupedSorts =
      mapOf(
        "metadata.titleSort" to grouped.field(G_TITLE_SORT, String::class.java)!!.unicode3(),
        "name" to grouped.field(G_NAME, String::class.java)!!.unicode3(),
        "createdDate" to grouped.field(G_CREATED_DATE)!!,
        "created" to grouped.field(G_CREATED_DATE)!!,
        "lastModifiedDate" to grouped.field(G_LAST_MODIFIED_DATE)!!,
        "lastModified" to grouped.field(G_LAST_MODIFIED_DATE)!!,
        "booksMetadata.releaseDate" to grouped.field(G_RELEASE_DATE)!!,
        "readDate" to grouped.field(G_READ_DATE)!!,
        "booksCount" to grouped.field(G_BOOKS_COUNT)!!,
        "random" to DSL.rand(),
      )

    val relevanceIds = seriesIds.orEmpty() + collectionIds.orEmpty()
    val orderBy =
      pageable.sort.mapNotNull {
        if (it.property == "relevance" && relevanceIds.isNotEmpty())
          gItemId.sortByValues(relevanceIds, it.isAscending)
        else
          it.toSortField(groupedSorts)
      }
    // deterministic tie-breaker for stable pagination
    val orderByStable = if (orderBy.isNotEmpty() && pageable.sort.none { it.property == "random" }) orderBy + gItemId.asc() else orderBy

    val pageItems =
      if (pageable.isEmpty())
        emptyList()
      else
        dslRO
          .select(gKind, gItemId)
          .from(grouped)
          .orderBy(orderByStable)
          .apply { if (pageable.isPaged) limit(pageable.pageSize).offset(pageable.offset) }
          .fetch()
          .map { it.value1() to it.value2() }

    val pageSeriesIds = pageItems.filter { it.first == KIND_SERIES }.map { it.second }
    val pageCollectionIds = pageItems.filter { it.first == KIND_COLLECTION }.map { it.second }

    val seriesById =
      if (pageSeriesIds.isEmpty())
        emptyMap()
      else
        dslRO.withTempTable(batchSize, pageSeriesIds).use { tempTable ->
          dslRO
            .selectBase(context.userId)
            .where(s.ID.`in`(tempTable.selectTempStrings()))
            .groupBy(*groupFields)
            .fetchAndMap(dslRO)
            .associateBy { it.id }
        }

    val collectionsById =
      if (pageCollectionIds.isEmpty())
        emptyMap()
      else
        collectionRepository
          .findByIds(pageCollectionIds, context)
          .associate { it.id to it.toDto() }

    val dtos =
      pageItems.mapNotNull { (kind, id) ->
        when (kind) {
          KIND_SERIES -> seriesById[id]?.let { SeriesGroupedItemDto(series = it) }
          KIND_COLLECTION -> collectionsById[id]?.let { SeriesGroupedItemDto(collection = it) }
          else -> null
        }
      }

    val count =
      if (pageable.isUnpaged)
        dtos.size
      else
        dslRO.fetchCount(grouped)

    val pageSort = if (orderBy.isNotEmpty()) pageable.sort else Sort.unsorted()
    return PageImpl(
      dtos,
      if (pageable.isPaged)
        PageRequest.of(pageable.pageNumber, pageable.pageSize, pageSort)
      else
        PageRequest.of(0, maxOf(count, 20), pageSort),
      count.toLong(),
    )
  }

  private fun <R : Record> SelectJoinStep<R>.applyJoins(joins: Set<RequiredJoin>): SelectJoinStep<R> {
    var step = this
    joins.forEach { join ->
      when (join) {
        is RequiredJoin.Collection -> {
          val csAlias = csAlias(join.collectionId)
          step = step.leftJoin(csAlias).on(s.ID.eq(csAlias.SERIES_ID).and(csAlias.COLLECTION_ID.eq(join.collectionId)))
        }
        // always joined
        is RequiredJoin.ReadProgress -> Unit
        RequiredJoin.SeriesMetadata -> Unit
        // Book joins - not needed
        RequiredJoin.BookMetadata -> Unit
        RequiredJoin.BookMetadataAggregation -> Unit
        RequiredJoin.Media -> Unit
        is RequiredJoin.ReadList -> Unit
      }
    }
    return step
  }

  override fun findAllRecentlyUpdated(
    search: SeriesSearch,
    context: SearchContext,
    pageable: Pageable,
  ): Page<SeriesDto> {
    requireNotNull(context.userId) { "Missing userId in search context" }

    val (conditions, joins) = SeriesSearchHelper(context).toCondition(search.condition)
    val conditionsRefined = conditions.and(s.CREATED_DATE.notEqual(s.LAST_MODIFIED_DATE))

    return findAll(conditionsRefined, context.userId, pageable, joins, search.fullTextSearch)
  }

  override fun countByFirstCharacter(
    search: SeriesSearch,
    context: SearchContext,
  ): List<GroupCountDto> {
    requireNotNull(context.userId) { "Missing userId in search context" }

    val (conditions, joins) = SeriesSearchHelper(context).toCondition(search.condition)
    val conditionsRefined = conditions.and(search.regexSearch?.let { it.second.toColumn().likeRegex(it.first) } ?: DSL.noCondition())

    val seriesIds = luceneHelper.searchEntitiesIds(search.fullTextSearch, LuceneEntity.Series)
    val searchCondition = s.ID.inOrNoCondition(seriesIds)

    val firstChar = lower(substring(d.TITLE_SORT, 1, 1))
    return dslRO
      .select(firstChar, count())
      .from(s)
      .leftJoin(d)
      .on(s.ID.eq(d.SERIES_ID))
      .leftJoin(bma)
      .on(s.ID.eq(bma.SERIES_ID))
      .leftJoin(rs)
      .on(s.ID.eq(rs.SERIES_ID))
      .and(readProgressConditionSeries(context.userId))
      .apply {
        joins.forEach { join ->
          when (join) {
            is RequiredJoin.Collection -> {
              val csAlias = csAlias(join.collectionId)
              leftJoin(csAlias).on(s.ID.eq(csAlias.SERIES_ID).and(csAlias.COLLECTION_ID.eq(join.collectionId)))
            }
            // always joined
            is RequiredJoin.ReadProgress -> Unit
            RequiredJoin.SeriesMetadata -> Unit
            // Book joins - not needed
            RequiredJoin.Media -> Unit
            RequiredJoin.BookMetadata -> Unit
            RequiredJoin.BookMetadataAggregation -> Unit
            is RequiredJoin.ReadList -> Unit
          }
        }
      }.where(conditionsRefined)
      .and(searchCondition)
      .groupBy(firstChar)
      .map {
        GroupCountDto(it.value1(), it.value2())
      }
  }

  override fun findByIdOrNull(
    seriesId: String,
    userId: String,
  ): SeriesDto? =
    dslRO
      .selectBase(userId)
      .where(s.ID.eq(seriesId))
      .groupBy(*groupFields)
      .fetchAndMap(dslRO)
      .firstOrNull()

  private fun DSLContext.selectBase(
    userId: String,
    joins: Set<RequiredJoin> = emptySet(),
  ): SelectOnConditionStep<Record> =
    this
      .select(*groupFields)
      .from(s)
      .leftJoin(d)
      .on(s.ID.eq(d.SERIES_ID))
      .leftJoin(bma)
      .on(s.ID.eq(bma.SERIES_ID))
      .leftJoin(rs)
      .on(s.ID.eq(rs.SERIES_ID))
      .and(readProgressConditionSeries(userId))
      .apply {
        joins.forEach { join ->
          when (join) {
            is RequiredJoin.Collection -> {
              val csAlias = csAlias(join.collectionId)
              leftJoin(csAlias).on(s.ID.eq(csAlias.SERIES_ID).and(csAlias.COLLECTION_ID.eq(join.collectionId)))
            }
            // always joined
            is RequiredJoin.ReadProgress -> Unit
            RequiredJoin.SeriesMetadata -> Unit
            // Book joins - not needed
            RequiredJoin.BookMetadata -> Unit
            RequiredJoin.BookMetadataAggregation -> Unit
            RequiredJoin.Media -> Unit
            is RequiredJoin.ReadList -> Unit
          }
        }
      }

  private fun findAll(
    conditions: Condition,
    userId: String,
    pageable: Pageable,
    joins: Set<RequiredJoin> = emptySet(),
    searchTerm: String? = null,
  ): Page<SeriesDto> {
    val seriesIds = luceneHelper.searchEntitiesIds(searchTerm, LuceneEntity.Series)
    val searchCondition = s.ID.inOrNoCondition(seriesIds)

    val orderBy =
      pageable.sort.mapNotNull {
        if (it.property == "relevance" && !seriesIds.isNullOrEmpty()) {
          s.ID.sortByValues(seriesIds, it.isAscending)
        } else {
          if (it.property == "collection.number") {
            val collectionId = joins.filterIsInstance<RequiredJoin.Collection>().firstOrNull()?.collectionId ?: return@mapNotNull null
            val f = csAlias(collectionId).NUMBER
            if (it.isAscending) f.asc() else f.desc()
          } else {
            it.toSortField(sorts)
          }
        }
      }

    val dtos =
      if (pageable.isEmpty())
        emptyList()
      else
        dslRO
          .selectBase(userId, joins)
          .where(conditions)
          .and(searchCondition)
          .orderBy(orderBy)
          .apply { if (pageable.isPaged) limit(pageable.pageSize).offset(pageable.offset) }
          .fetchAndMap(dslRO)

    val count =
      if (pageable.isUnpaged)
        dtos.size
      else
        dslRO
          .select(countDistinct(s.ID))
          .from(s)
          .leftJoin(d)
          .on(s.ID.eq(d.SERIES_ID))
          .leftJoin(bma)
          .on(s.ID.eq(bma.SERIES_ID))
          .leftJoin(rs)
          .on(s.ID.eq(rs.SERIES_ID))
          .and(readProgressConditionSeries(userId))
          .apply {
            joins.forEach { join ->
              when (join) {
                is RequiredJoin.Collection -> {
                  val csAlias = csAlias(join.collectionId)
                  leftJoin(csAlias).on(s.ID.eq(csAlias.SERIES_ID).and(csAlias.COLLECTION_ID.eq(join.collectionId)))
                }
                // always joined
                is RequiredJoin.ReadProgress -> Unit
                RequiredJoin.SeriesMetadata -> Unit
                // Book joins - not needed
                RequiredJoin.BookMetadata -> Unit
                RequiredJoin.BookMetadataAggregation -> Unit
                RequiredJoin.Media -> Unit
                is RequiredJoin.ReadList -> Unit
              }
            }
          }.where(conditions)
          .and(searchCondition)
          .fetchOne(countDistinct(s.ID)) ?: 0

    val pageSort = if (orderBy.isNotEmpty()) pageable.sort else Sort.unsorted()
    return PageImpl(
      dtos,
      if (pageable.isPaged)
        PageRequest.of(pageable.pageNumber, pageable.pageSize, pageSort)
      else
        PageRequest.of(0, maxOf(count, 20), pageSort),
      count.toLong(),
    )
  }

  private fun readProgressConditionSeries(userId: String): Condition = rs.USER_ID.eq(userId).or(rs.USER_ID.isNull)

  private fun ResultQuery<Record>.fetchAndMap(dsl: DSLContext): MutableList<SeriesDto> {
    val records = fetch()
    val seriesIds = records.getValues(s.ID)

    lateinit var genres: Map<String, List<String>>
    lateinit var tags: Map<String, List<String>>
    lateinit var sharingLabels: Map<String, List<String>>
    lateinit var links: Map<String, List<WebLinkDto>>
    lateinit var alternateTitles: Map<String, List<AlternateTitleDto>>
    lateinit var aggregatedAuthors: Map<String, List<AuthorDto>>
    lateinit var aggregatedNotableEntities: Map<String, List<NotableEntityDto>>
    lateinit var aggregatedTags: Map<String, List<String>>

    dsl.withTempTable(batchSize, seriesIds).use { tempTable ->
      genres =
        dsl
          .selectFrom(g)
          .where(g.SERIES_ID.`in`(tempTable.selectTempStrings()))
          .groupBy({ it.seriesId }, { it.genre })

      tags =
        dsl
          .selectFrom(st)
          .where(st.SERIES_ID.`in`(tempTable.selectTempStrings()))
          .groupBy({ it.seriesId }, { it.tag })

      sharingLabels =
        dsl
          .selectFrom(sl)
          .where(sl.SERIES_ID.`in`(tempTable.selectTempStrings()))
          .groupBy({ it.seriesId }, { it.label })

      links =
        dsl
          .selectFrom(slk)
          .where(slk.SERIES_ID.`in`(tempTable.selectTempStrings()))
          .groupBy({ it.seriesId }, { WebLinkDto(it.label, it.url) })

      alternateTitles =
        dsl
          .selectFrom(sat)
          .where(sat.SERIES_ID.`in`(tempTable.selectTempStrings()))
          .groupBy({ it.seriesId }, { AlternateTitleDto(it.label, it.title) })

      aggregatedAuthors =
        dsl
          .selectFrom(bmaa)
          .where(bmaa.SERIES_ID.`in`(tempTable.selectTempStrings()))
          .filter { it.name != null }
          .groupBy({ it.seriesId }, { AuthorDto(it.name, it.role) })

      aggregatedNotableEntities =
        dsl
          .selectFrom(bmane)
          .where(bmane.SERIES_ID.`in`(tempTable.selectTempStrings()))
          .filter { it.name != null }
          .groupBy({ it.seriesId }, { NotableEntityDto(it.kind, it.name) })

      aggregatedTags =
        dsl
          .selectFrom(bmat)
          .where(bmat.SERIES_ID.`in`(tempTable.selectTempStrings()))
          .groupBy({ it.seriesId }, { it.tag })
    }

    return records
      .map { rec ->
        val sr = rec.into(s)
        val dr = rec.into(d)
        val bmar = rec.into(bma)
        val rsr = rec.into(rs)
        val booksReadCount = rsr.readCount ?: 0
        val booksInProgressCount = rsr.inProgressCount ?: 0
        val booksUnreadCount = sr.bookCount - booksReadCount - booksInProgressCount

        sr.toDto(
          sr.bookCount,
          booksReadCount,
          booksUnreadCount,
          booksInProgressCount,
          dr.toDto(genres[sr.id].orEmpty().toSet(), tags[sr.id].orEmpty().toSet(), sharingLabels[sr.id].orEmpty().toSet(), links[sr.id].orEmpty(), alternateTitles[sr.id].orEmpty()),
          bmar.toDto(aggregatedAuthors[sr.id].orEmpty(), aggregatedNotableEntities[sr.id].orEmpty(), aggregatedTags[sr.id].orEmpty().toSet()),
        )
      }
  }

  private fun SearchField.toColumn() =
    when (this) {
      SearchField.TITLE -> d.TITLE
      SearchField.TITLE_SORT -> d.TITLE_SORT
    }

  private fun SeriesRecord.toDto(
    booksCount: Int,
    booksReadCount: Int,
    booksUnreadCount: Int,
    booksInProgressCount: Int,
    metadata: SeriesMetadataDto,
    booksMetadata: BookMetadataAggregationDto,
  ) = SeriesDto(
    id = id,
    libraryId = libraryId,
    name = name,
    url = URL(url).toFilePath(),
    created = createdDate,
    lastModified = lastModifiedDate,
    fileLastModified = fileLastModified,
    booksCount = booksCount,
    booksReadCount = booksReadCount,
    booksUnreadCount = booksUnreadCount,
    booksInProgressCount = booksInProgressCount,
    metadata = metadata,
    booksMetadata = booksMetadata,
    deleted = deletedDate != null,
    oneshot = oneshot,
  )

  private fun SeriesMetadataRecord.toDto(
    genres: Set<String>,
    tags: Set<String>,
    sharingLabels: Set<String>,
    links: List<WebLinkDto>,
    alternateTitles: List<AlternateTitleDto>,
  ) = SeriesMetadataDto(
    status = status,
    statusLock = statusLock,
    created = createdDate,
    lastModified = lastModifiedDate,
    title = title,
    titleLock = titleLock,
    titleSort = titleSort,
    titleSortLock = titleSortLock,
    summary = summary,
    summaryLock = summaryLock,
    readingDirection = readingDirection ?: "",
    readingDirectionLock = readingDirectionLock,
    publisher = publisher,
    publisherLock = publisherLock,
    ageRating = ageRating,
    ageRatingLock = ageRatingLock,
    language = language,
    languageLock = languageLock,
    genres = genres,
    genresLock = genresLock,
    tags = tags,
    tagsLock = tagsLock,
    totalBookCount = totalBookCount,
    totalBookCountLock = totalBookCountLock,
    sharingLabels = sharingLabels,
    sharingLabelsLock = sharingLabelsLock,
    links = links,
    linksLock = linksLock,
    alternateTitles = alternateTitles,
    alternateTitlesLock = alternateTitlesLock,
  )

  private fun BookMetadataAggregationRecord.toDto(
    authors: List<AuthorDto>,
    notableEntities: List<NotableEntityDto>,
    tags: Set<String>,
  ) = BookMetadataAggregationDto(
    authors = authors,
    notableEntities = notableEntities,
    tags = tags,
    releaseDate = releaseDate,
    summary = summary,
    summaryNumber = summaryNumber,
    created = createdDate,
    lastModified = lastModifiedDate,
  )
}
