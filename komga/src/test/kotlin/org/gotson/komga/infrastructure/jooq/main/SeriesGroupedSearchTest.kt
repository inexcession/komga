package org.gotson.komga.infrastructure.jooq.main

import com.ninjasquad.springmockk.MockkBean
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.application.query.UnpagedSorted
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.domain.model.ReadProgress
import org.gotson.komga.domain.model.ReadStatus
import org.gotson.komga.domain.model.SearchCondition
import org.gotson.komga.domain.model.SearchContext
import org.gotson.komga.domain.model.SearchOperator
import org.gotson.komga.domain.model.SeriesCollection
import org.gotson.komga.domain.model.SeriesSearch
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeLibrary
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.BookMetadataRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.KomgaUserRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.persistence.ReadProgressRepository
import org.gotson.komga.domain.persistence.SeriesCollectionRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.domain.service.KomgaUserLifecycle
import org.gotson.komga.domain.service.LibraryLifecycle
import org.gotson.komga.domain.service.SeriesLifecycle
import org.gotson.komga.domain.service.SeriesMetadataLifecycle
import org.gotson.komga.interfaces.api.rest.dto.SeriesGroupedItemDto
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import java.time.LocalDate
import java.time.LocalDateTime

@SpringBootTest
class SeriesGroupedSearchTest(
  @Autowired private val seriesDtoDao: SeriesDtoDao,
  @Autowired private val seriesRepository: SeriesRepository,
  @Autowired private val bookRepository: BookRepository,
  @Autowired private val bookMetadataRepository: BookMetadataRepository,
  @Autowired private val readProgressRepository: ReadProgressRepository,
  @Autowired private val userRepository: KomgaUserRepository,
  @Autowired private val seriesLifecycle: SeriesLifecycle,
  @Autowired private val seriesMetadataLifecycle: SeriesMetadataLifecycle,
  @Autowired private val libraryRepository: LibraryRepository,
  @Autowired private val libraryLifecycle: LibraryLifecycle,
  @Autowired private val userLifecycle: KomgaUserLifecycle,
  @Autowired private val collectionRepository: SeriesCollectionRepository,
) {
  private val library1 = makeLibrary()
  private val library2 = makeLibrary()
  private val user1 = KomgaUser("user1@example.org", "p")

  @MockkBean
  private lateinit var mockEventPublisher: ApplicationEventPublisher

  @BeforeAll
  fun setup() {
    every { mockEventPublisher.publishEvent(any()) } just Runs
    libraryRepository.insert(library1)
    libraryRepository.insert(library2)
    userRepository.insert(user1)
  }

  @BeforeEach
  fun resetMocks() {
    every { mockEventPublisher.publishEvent(any()) } just Runs
  }

  @AfterEach
  fun deleteSeries() {
    collectionRepository.deleteAll()
    seriesLifecycle.deleteMany(seriesRepository.findAll())
  }

  @AfterAll
  fun tearDown() {
    every { mockEventPublisher.publishEvent(any()) } just Runs
    userRepository.findAll().forEach {
      userLifecycle.deleteUser(it)
    }
    libraryRepository.findAll().forEach {
      libraryLifecycle.deleteLibrary(it)
    }
  }

  private fun SeriesGroupedItemDto.label() = series?.name ?: "col:${collection!!.name}"

  private fun createSeries(
    name: String,
    libraryId: String = library1.id,
    books: Int = 0,
  ) = makeSeries(name, libraryId).also { series ->
    seriesLifecycle.createSeries(series)
    if (books > 0) {
      seriesLifecycle.addBooks(series, (1..books).map { makeBook("$name-$it", libraryId = libraryId) })
      seriesLifecycle.sortBooks(series)
    }
  }

  @Test
  fun `given standalone series and a collection when searching grouped then collected series are replaced by the collection`() {
    createSeries("a")
    val b1 = createSeries("b1")
    val b2 = createSeries("b2")
    createSeries("d")
    collectionRepository.insert(SeriesCollection("b collection", seriesIds = listOf(b1.id, b2.id)))

    val page = seriesDtoDao.findAllGrouped(SeriesSearch(), SearchContext(user1), UnpagedSorted(Sort.by("metadata.titleSort")))

    assertThat(page.content.map { it.label() }).containsExactly("a", "col:b collection", "d")
    assertThat(page.totalElements).isEqualTo(3)
    assertThat(page.content[1].collection!!.seriesIds).containsExactly(b1.id, b2.id)
  }

  @Test
  fun `given a single series collection when searching grouped then the series is collapsed into the collection`() {
    createSeries("a")
    val b = createSeries("b")
    collectionRepository.insert(SeriesCollection("col", seriesIds = listOf(b.id)))

    val page = seriesDtoDao.findAllGrouped(SeriesSearch(), SearchContext(user1), UnpagedSorted(Sort.by("metadata.titleSort")))

    assertThat(page.content.map { it.label() }).containsExactly("a", "col:col")
  }

  @Test
  fun `given a series in 2 collections when searching grouped then both collections are returned`() {
    createSeries("a")
    val b = createSeries("b")
    collectionRepository.insert(SeriesCollection("col1", seriesIds = listOf(b.id)))
    collectionRepository.insert(SeriesCollection("col2", seriesIds = listOf(b.id)))

    val page = seriesDtoDao.findAllGrouped(SeriesSearch(), SearchContext(user1), UnpagedSorted(Sort.by("metadata.titleSort")))

    assertThat(page.content.map { it.label() }).containsExactly("a", "col:col1", "col:col2")
  }

  @Test
  fun `given series and collections when searching grouped with pagination then pages are accurate`() {
    createSeries("a")
    val b1 = createSeries("b1")
    val b2 = createSeries("b2")
    createSeries("c")
    createSeries("d")
    collectionRepository.insert(SeriesCollection("b collection", seriesIds = listOf(b1.id, b2.id)))

    val sort = Sort.by("metadata.titleSort")
    val page0 = seriesDtoDao.findAllGrouped(SeriesSearch(), SearchContext(user1), PageRequest.of(0, 2, sort))
    val page1 = seriesDtoDao.findAllGrouped(SeriesSearch(), SearchContext(user1), PageRequest.of(1, 2, sort))

    assertThat(page0.content.map { it.label() }).containsExactly("a", "col:b collection")
    assertThat(page1.content.map { it.label() }).containsExactly("c", "d")
    assertThat(page0.totalElements).isEqualTo(4)
    assertThat(page0.totalPages).isEqualTo(2)
  }

  @Test
  fun `given series and collections when searching grouped sorted by books count then collections use the sum of books`() {
    createSeries("a", books = 3)
    val b1 = createSeries("b1", books = 1)
    val b2 = createSeries("b2", books = 1)
    createSeries("c", books = 1)
    collectionRepository.insert(SeriesCollection("col", seriesIds = listOf(b1.id, b2.id)))

    val page = seriesDtoDao.findAllGrouped(SeriesSearch(), SearchContext(user1), UnpagedSorted(Sort.by(Sort.Order.desc("booksCount"))))

    assertThat(page.content.map { it.label() }).containsExactly("a", "col:col", "c")
  }

  @Test
  fun `given series and collections when searching grouped sorted by release date then collections use the earliest release date`() {
    val a = createSeries("a", books = 1)
    val b1 = createSeries("b1", books = 1)
    val b2 = createSeries("b2", books = 1)
    listOf(a to 5L, b1 to 1L, b2 to 10L).forEach { (series, daysAgo) ->
      val book = bookRepository.findAllBySeriesId(series.id).first()
      bookMetadataRepository.findById(book.id).let { bookMetadataRepository.update(it.copy(releaseDate = LocalDate.now().minusDays(daysAgo))) }
      seriesMetadataLifecycle.aggregateMetadata(series)
    }
    collectionRepository.insert(SeriesCollection("col", seriesIds = listOf(b1.id, b2.id)))

    val page = seriesDtoDao.findAllGrouped(SeriesSearch(), SearchContext(user1), UnpagedSorted(Sort.by("booksMetadata.releaseDate")))

    // col min release date is 10 days ago
    assertThat(page.content.map { it.label() }).containsExactly("col:col", "a")
  }

  @Test
  fun `given series and collections when searching grouped sorted by read date then collections use the most recent read date`() {
    val a = createSeries("a", books = 1)
    val b1 = createSeries("b1", books = 1)
    val b2 = createSeries("b2", books = 1)
    val now = LocalDateTime.now()
    listOf(a to 5L, b1 to 10L, b2 to 1L).forEach { (series, daysAgo) ->
      val book = bookRepository.findAllBySeriesId(series.id).first()
      readProgressRepository.save(ReadProgress(book.id, user1.id, 1, true, readDate = now.minusDays(daysAgo)))
    }
    collectionRepository.insert(SeriesCollection("col", seriesIds = listOf(b1.id, b2.id)))

    val page = seriesDtoDao.findAllGrouped(SeriesSearch(), SearchContext(user1), UnpagedSorted(Sort.by(Sort.Order.desc("readDate"))))

    // col max read date is 1 day ago (from b2)
    assertThat(page.content.map { it.label() }).containsExactly("col:col", "a")
  }

  @Test
  fun `given series and collections when searching grouped with a filter then collections appear only if at least one series matches`() {
    createSeries("a", books = 1)
    val b1 = createSeries("b1", books = 1)
    val b2 = createSeries("b2", books = 1)
    val c1 = createSeries("c1", books = 1)
    collectionRepository.insert(SeriesCollection("col b", seriesIds = listOf(b1.id, b2.id)))
    collectionRepository.insert(SeriesCollection("col c", seriesIds = listOf(c1.id)))

    // b1 is read
    bookRepository.findAllBySeriesId(b1.id).forEach { readProgressRepository.save(ReadProgress(it.id, user1.id, 1, true)) }

    run {
      val search = SeriesSearch(SearchCondition.ReadStatus(SearchOperator.Is(ReadStatus.READ)))
      val page = seriesDtoDao.findAllGrouped(search, SearchContext(user1), UnpagedSorted(Sort.by("metadata.titleSort")))
      assertThat(page.content.map { it.label() }).containsExactly("col:col b")
    }

    run {
      val search = SeriesSearch(SearchCondition.ReadStatus(SearchOperator.Is(ReadStatus.UNREAD)))
      val page = seriesDtoDao.findAllGrouped(search, SearchContext(user1), UnpagedSorted(Sort.by("metadata.titleSort")))
      assertThat(page.content.map { it.label() }).containsExactly("a", "col:col b", "col:col c")
    }
  }

  @Test
  fun `given series in restricted libraries when searching grouped then restricted series do not leak collections`() {
    createSeries("a", library1.id)
    val b1 = createSeries("b1", library1.id)
    val b2 = createSeries("b2", library2.id)
    val c1 = createSeries("c1", library2.id)
    collectionRepository.insert(SeriesCollection("col b", seriesIds = listOf(b1.id, b2.id)))
    collectionRepository.insert(SeriesCollection("col c", seriesIds = listOf(c1.id)))

    val restrictedUser = user1.copy(sharedAllLibraries = false, sharedLibrariesIds = setOf(library1.id))
    val page = seriesDtoDao.findAllGrouped(SeriesSearch(), SearchContext(restrictedUser), UnpagedSorted(Sort.by("metadata.titleSort")))

    assertThat(page.content.map { it.label() }).containsExactly("a", "col:col b")
    assertThat(page.content[1].collection!!.seriesIds).containsExactly(b1.id)
  }

  @Test
  fun `given no series when searching grouped then result is empty`() {
    val page = seriesDtoDao.findAllGrouped(SeriesSearch(), SearchContext(user1), Pageable.ofSize(20))

    assertThat(page.content).isEmpty()
    assertThat(page.totalElements).isEqualTo(0)
  }
}
