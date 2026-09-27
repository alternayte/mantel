package com.mantel.app.timeline

import com.mantel.app.api.ItemView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * One photograph on the timeline, wherever it lives. `phone` is set when it is on this phone, `item`
 * when the library holds it; a photograph in both is one tile, drawn from the phone, because the
 * phone's own copy is instant and full quality.
 */
data class Tile(
    val key: String,
    /** Epoch milliseconds. */
    val takenAt: Long,
    val phone: RollPhoto? = null,
    val item: ItemView? = null,
) {
    /** The badge: the library holds it, or it is only on the phone so far. */
    val backedUp: Boolean get() = item != null

    val video: Boolean get() = phone?.video ?: item?.kind.equals("video", ignoreCase = true)
}

/** One day of the timeline, in the order the day's photographs were taken, newest first. */
data class Day(val date: LocalDate, val tiles: List<Tile>)

/** What the grid draws, in order: a day's heading, then its photographs. */
sealed interface Cell {
    val key: String

    data class Heading(val day: Day) : Cell {
        override val key: String get() = "d:${day.date}"
    }

    data class Photo(val tile: Tile) : Cell {
        override val key: String get() = tile.key
    }
}

/** Where a month starts in [Timeline.cells], for the handle that scrolls by month. */
data class Month(val label: String, val cell: Int)

data class Timeline(val days: List<Day>) {
    val cells: List<Cell> by lazy { days.flatMap { day -> listOf(Cell.Heading(day)) + day.tiles.map { Cell.Photo(it) } } }

    val tiles: List<Tile> by lazy { days.flatMap { it.tiles } }

    val months: List<Month> by lazy {
        val found = mutableListOf<Month>()
        var index = 0
        var last: Pair<Int, Int>? = null
        days.forEach { day ->
            val month = day.date.year to day.date.monthValue
            if (month != last) {
                found += Month(MONTH.format(day.date), index)
                last = month
            }
            index += 1 + day.tiles.size
        }
        found
    }

    companion object {
        val EMPTY = Timeline(emptyList())
        private val MONTH = DateTimeFormatter.ofPattern("MMMM yyyy")
    }
}

/**
 * How far apart a phone's `DATE_TAKEN` and the library's `takenAt` can be for the same photograph.
 * The library reads EXIF, and a camera that wrote no offset has its wall clock read as UTC, so the
 * two differ by up to the widest time zone.
 */
private const val ZONE_SLACK_MS = 14L * 60 * 60 * 1000

/**
 * The camera roll and the library, as one timeline, each photograph once.
 *
 * A photograph on the phone whose hash is a library item's is one tile. One the index has not hashed
 * yet is matched to a library item of the same size taken at about the same time, and that item is
 * held back: until the hash says otherwise, two photographs of one size from one moment are one
 * photograph, because showing it twice is the failure a person notices.
 *
 * The library arrives a page at a time, newest first. While more of it is to come, a photograph on
 * the phone older than the last page is left out, because its library copy may be on the next page
 * and it would show twice until that page arrived. The boundary is the page's oldest photograph and
 * nothing earlier: a margin wide enough to cover every time zone hid a whole day of new photographs
 * behind a first page taken the same day, and the grid asks for the next page before it gets there.
 */
fun buildTimeline(
    roll: List<RollPhoto>,
    library: List<ItemView>,
    libraryComplete: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
): Timeline {
    val libraryTimes = library.associate { it.id to takenAtOf(it) }
    val byHash = library.filter { it.contentHash != null }.associateBy { it.contentHash }
    val bySize = library.groupBy { it.byteSize }
    val oldest = if (libraryComplete) null else libraryTimes.values.minOrNull()
    val consumed = HashSet<String>()
    val tiles = ArrayList<Tile>(roll.size + library.size)

    roll.forEach { photo ->
        if (oldest != null && photo.takenAt < oldest) return@forEach
        val match =
            if (photo.hash != null) {
                byHash[photo.hash]
            } else {
                bySize[photo.size]?.firstOrNull {
                    it.id !in consumed && abs(libraryTimes.getValue(it.id) - photo.takenAt) <= ZONE_SLACK_MS
                }
            }
        match?.let { consumed += it.id }
        tiles += Tile(key = "p:${photo.uri}", takenAt = photo.takenAt, phone = photo, item = match)
    }
    library.forEach { item ->
        if (item.id in consumed) return@forEach
        tiles += Tile(key = "l:${item.id}", takenAt = libraryTimes.getValue(item.id), item = item)
    }

    tiles.sortWith(compareByDescending<Tile> { it.takenAt }.thenBy { it.key })
    val days =
        tiles
            .groupBy { Instant.ofEpochMilli(it.takenAt).atZone(zone).toLocalDate() }
            .map { (date, dayTiles) -> Day(date, dayTiles) }
    return Timeline(days)
}

/** A library item's moment, in epoch milliseconds. An item from before `takenAt` existed sorts last. */
fun takenAtOf(item: ItemView): Long = item.takenAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

private val THIS_YEAR = DateTimeFormatter.ofPattern("EEE d MMM")
private val OTHER_YEAR = DateTimeFormatter.ofPattern("EEE d MMM yyyy")

/** A day's heading, as a person says it. */
fun dayLabel(
    date: LocalDate,
    today: LocalDate = LocalDate.now(),
): String =
    when {
        date == today -> "Today"
        date == today.minusDays(1) -> "Yesterday"
        date.year == today.year -> THIS_YEAR.format(date)
        else -> OTHER_YEAR.format(date)
    }
