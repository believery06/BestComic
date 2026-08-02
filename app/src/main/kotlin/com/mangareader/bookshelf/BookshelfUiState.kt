package com.mangareader.bookshelf

import androidx.compose.runtime.saveable.Saver

/**
 * 书架筛选条件。
 */
internal enum class BookshelfFilter { ALL, FAVORITES, UNREAD, READING, READ, NEW }

/**
 * 书架排序方式。
 */
internal enum class BookshelfSort { NAME, RECENT, PROGRESS }

/**
 * 书架视图模式。
 */
internal enum class BookshelfViewMode { GRID, LIST }

internal val BookshelfFilterSaver = Saver<BookshelfFilter, String>(
    save = { it.name },
    restore = { runCatching { enumValueOf<BookshelfFilter>(it) }.getOrNull() }
)

internal val BookshelfSortSaver = Saver<BookshelfSort, String>(
    save = { it.name },
    restore = { runCatching { enumValueOf<BookshelfSort>(it) }.getOrNull() }
)

internal val BookshelfViewModeSaver = Saver<BookshelfViewMode, String>(
    save = { it.name },
    restore = { runCatching { enumValueOf<BookshelfViewMode>(it) }.getOrNull() }
)
