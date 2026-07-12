package com.mangareader.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "manga_reader_settings")

class SettingsRepository(private val context: Context) {

    companion object {
        private val KEY_PROGRESS_PREFIX = "progress_"
        private val KEY_PROGRESS_TIME_PREFIX = "progress_time_"
        private val KEY_TOTAL_PREFIX = "total_"
        private val KEY_DUAL_PAGE = booleanPreferencesKey("dual_page")
        private val KEY_RTL = booleanPreferencesKey("rtl")
        private val KEY_IMMERSIVE = booleanPreferencesKey("immersive")
        private val KEY_BG_COLOR = stringPreferencesKey("bg_color")
        private val KEY_AUTO_CROP = booleanPreferencesKey("auto_crop")
        private val KEY_BRIGHTNESS = floatPreferencesKey("brightness")
        private val KEY_CONTRAST = floatPreferencesKey("contrast")
        private val KEY_ROTATION = intPreferencesKey("rotation")
        private val KEY_DUAL_PAGE_SPACING = intPreferencesKey("dual_page_spacing")
        private val KEY_BOOKSHELF_ROOT = stringPreferencesKey("bookshelf_root")
        private val KEY_SCROLL_MODE = stringPreferencesKey("scroll_mode")
        private val KEY_GRAYSCALE = booleanPreferencesKey("grayscale")
        private val KEY_SHARPEN = booleanPreferencesKey("sharpen")
        private val KEY_DENOISE = booleanPreferencesKey("denoise")
        private val KEY_DUAL_PAGE_OFFSET = intPreferencesKey("dual_page_offset")
        private val KEY_BOOKMARKS = stringPreferencesKey("bookmarks_map")
        private val KEY_FAVORITES = stringPreferencesKey("favorites_set")
        private val KEY_CATEGORIES = stringPreferencesKey("categories_list")
        private val KEY_COMIC_CATEGORIES = stringPreferencesKey("comic_categories")
        private val KEY_ZOOM_MODE = stringPreferencesKey("zoom_mode")
        private val KEY_MIRROR = booleanPreferencesKey("mirror")
        private val KEY_PAGE_SHADOW = booleanPreferencesKey("page_shadow")
        private val KEY_AUTO_PAGE_INTERVAL = intPreferencesKey("auto_page_interval")
        private val KEY_GAMMA = floatPreferencesKey("gamma")
        private val KEY_DUAL_PAGE_START_ONE = booleanPreferencesKey("dual_page_start_one")
        private val KEY_PERMISSION_ACK = booleanPreferencesKey("permission_acknowledged")
        private val KEY_HELP_DONT_SHOW = booleanPreferencesKey("help_dont_show")
        private val KEY_SATURATION = floatPreferencesKey("saturation")
        private val KEY_NIGHT_MODE = booleanPreferencesKey("night_mode")
        private val KEY_EYE_CARE = booleanPreferencesKey("eye_care")
        private val KEY_VOLUME_KEY_NAV = booleanPreferencesKey("volume_key_nav")
        private val KEY_PAGE_ANIMATION = stringPreferencesKey("page_animation")
        private val KEY_RANDOM_ANIMATION = booleanPreferencesKey("random_animation")
        private val KEY_BG_TEXTURE = stringPreferencesKey("bg_texture")
        private val KEY_TRACKED_URIS = stringPreferencesKey("tracked_uris")
        private const val MAX_TRACKED_URIS = 500
        private val KEY_ENABLE_ZOOM = booleanPreferencesKey("enable_zoom")
        private val KEY_SHARPEN_STRENGTH = floatPreferencesKey("sharpen_strength")
        private val KEY_DENOISE_STRENGTH = floatPreferencesKey("denoise_strength")
        private val KEY_TAP_ZONE_SIZE = stringPreferencesKey("tap_zone_size")
        private val KEY_SCALE_FILTER = stringPreferencesKey("scale_filter")
        private val KEY_MAGNIFIER = booleanPreferencesKey("magnifier_enabled")
        private val KEY_APP_THEME = stringPreferencesKey("app_theme")

        // 漫画评分、短评、标签
        private val KEY_COMIC_EXTRAS_PREFIX = "extras_"
        // 用户书单
        private val KEY_READING_LISTS = stringPreferencesKey("reading_lists")
        // 阅读统计
        private val KEY_READING_STATS = stringPreferencesKey("reading_stats")

        private val json = Json { ignoreUnknownKeys = true }
    }

    val readerSettings: Flow<ReaderSettings> = context.dataStore.data.map { prefs ->
        ReaderSettings(
            dualPage = prefs[KEY_DUAL_PAGE] ?: false,
            rtl = prefs[KEY_RTL] ?: true,
            immersive = prefs[KEY_IMMERSIVE] ?: true,
            backgroundColor = prefs[KEY_BG_COLOR] ?: "#FF000000",
            autoCrop = prefs[KEY_AUTO_CROP] ?: false,
            brightness = prefs[KEY_BRIGHTNESS] ?: 1f,
            contrast = prefs[KEY_CONTRAST] ?: 1f,
            rotation = prefs[KEY_ROTATION] ?: 0,
            dualPageSpacing = prefs[KEY_DUAL_PAGE_SPACING] ?: 0,
            scrollMode = runCatching { ScrollMode.valueOf(prefs[KEY_SCROLL_MODE] ?: "PAGE") }
                .getOrDefault(ScrollMode.PAGE),
            grayscale = prefs[KEY_GRAYSCALE] ?: false,
            sharpen = prefs[KEY_SHARPEN] ?: false,
            denoise = prefs[KEY_DENOISE] ?: false,
            dualPageOffset = prefs[KEY_DUAL_PAGE_OFFSET] ?: 0,
            zoomMode = runCatching { ZoomMode.valueOf(prefs[KEY_ZOOM_MODE] ?: "FREE") }
                .getOrDefault(ZoomMode.FREE),
            mirror = prefs[KEY_MIRROR] ?: false,
            pageShadow = prefs[KEY_PAGE_SHADOW] ?: false,
            autoPageInterval = prefs[KEY_AUTO_PAGE_INTERVAL] ?: 0,
            gamma = prefs[KEY_GAMMA] ?: 1f,
            dualPageStartOne = prefs[KEY_DUAL_PAGE_START_ONE] ?: false,
            saturation = prefs[KEY_SATURATION] ?: 1f,
            nightMode = prefs[KEY_NIGHT_MODE] ?: false,
            eyeCare = prefs[KEY_EYE_CARE] ?: false,
            volumeKeyNav = prefs[KEY_VOLUME_KEY_NAV] ?: false,
            pageAnimation = runCatching { PageAnimation.valueOf(prefs[KEY_PAGE_ANIMATION] ?: "NONE") }
                .getOrDefault(PageAnimation.NONE),
            randomAnimation = prefs[KEY_RANDOM_ANIMATION] ?: false,
            enableZoom = prefs[KEY_ENABLE_ZOOM] ?: false,
            sharpenStrength = prefs[KEY_SHARPEN_STRENGTH] ?: 1f,
            denoiseStrength = prefs[KEY_DENOISE_STRENGTH] ?: 1f,
            tapZoneSize = runCatching { TapZoneSize.valueOf(prefs[KEY_TAP_ZONE_SIZE] ?: "MEDIUM") }
                .getOrDefault(TapZoneSize.MEDIUM),
            scaleFilter = runCatching { ScaleFilter.valueOf(prefs[KEY_SCALE_FILTER] ?: "BILINEAR") }
                .getOrDefault(ScaleFilter.BILINEAR),
            magnifierEnabled = prefs[KEY_MAGNIFIER] ?: false,
            backgroundTexture = runCatching { BackgroundTexture.valueOf(prefs[KEY_BG_TEXTURE] ?: "NONE") }
                .getOrDefault(BackgroundTexture.NONE),
            appTheme = runCatching { AppTheme.valueOf(prefs[KEY_APP_THEME] ?: "INK") }
                .getOrDefault(AppTheme.INK)
        )
    }

    suspend fun saveReaderSettings(settings: ReaderSettings) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DUAL_PAGE] = settings.dualPage
            prefs[KEY_RTL] = settings.rtl
            prefs[KEY_IMMERSIVE] = settings.immersive
            prefs[KEY_BG_COLOR] = settings.backgroundColor
            prefs[KEY_AUTO_CROP] = settings.autoCrop
            prefs[KEY_BRIGHTNESS] = settings.brightness
            prefs[KEY_CONTRAST] = settings.contrast
            prefs[KEY_ROTATION] = settings.rotation
            prefs[KEY_DUAL_PAGE_SPACING] = settings.dualPageSpacing
            prefs[KEY_SCROLL_MODE] = settings.scrollMode.name
            prefs[KEY_GRAYSCALE] = settings.grayscale
            prefs[KEY_SHARPEN] = settings.sharpen
            prefs[KEY_DENOISE] = settings.denoise
            prefs[KEY_DUAL_PAGE_OFFSET] = settings.dualPageOffset
            prefs[KEY_ZOOM_MODE] = settings.zoomMode.name
            prefs[KEY_MIRROR] = settings.mirror
            prefs[KEY_PAGE_SHADOW] = settings.pageShadow
            prefs[KEY_AUTO_PAGE_INTERVAL] = settings.autoPageInterval
            prefs[KEY_GAMMA] = settings.gamma
            prefs[KEY_DUAL_PAGE_START_ONE] = settings.dualPageStartOne
            prefs[KEY_SATURATION] = settings.saturation
            prefs[KEY_NIGHT_MODE] = settings.nightMode
            prefs[KEY_EYE_CARE] = settings.eyeCare
            prefs[KEY_VOLUME_KEY_NAV] = settings.volumeKeyNav
            prefs[KEY_PAGE_ANIMATION] = settings.pageAnimation.name
            prefs[KEY_RANDOM_ANIMATION] = settings.randomAnimation
            prefs[KEY_BG_TEXTURE] = settings.backgroundTexture.name
            prefs[KEY_ENABLE_ZOOM] = settings.enableZoom
            prefs[KEY_SHARPEN_STRENGTH] = settings.sharpenStrength
            prefs[KEY_DENOISE_STRENGTH] = settings.denoiseStrength
            prefs[KEY_TAP_ZONE_SIZE] = settings.tapZoneSize.name
            prefs[KEY_SCALE_FILTER] = settings.scaleFilter.name
            prefs[KEY_MAGNIFIER] = settings.magnifierEnabled
            prefs[KEY_APP_THEME] = settings.appTheme.name
        }
    }

    fun getProgress(uri: Uri): Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[intPreferencesKey(KEY_PROGRESS_PREFIX + uri.toString())] ?: 0
    }

    suspend fun saveProgress(uri: Uri, page: Int) {
        context.dataStore.edit { prefs ->
            val key = intPreferencesKey(KEY_PROGRESS_PREFIX + uri.toString())
            prefs[key] = page
            val timeKey = longPreferencesKey(KEY_PROGRESS_TIME_PREFIX + uri.toString())
            prefs[timeKey] = System.currentTimeMillis()
            // 跟踪已记录进度的 URI，用于 getAllProgress 查询
            val currentUri = uri.toString()
            val tracked = decodeTrackedUris(prefs[KEY_TRACKED_URIS]).toMutableList()
            tracked.remove(currentUri)
            tracked.add(0, currentUri)
            tracked.drop(MAX_TRACKED_URIS).forEach { staleUri ->
                prefs.remove(intPreferencesKey(KEY_PROGRESS_PREFIX + staleUri))
                prefs.remove(longPreferencesKey(KEY_PROGRESS_TIME_PREFIX + staleUri))
                prefs.remove(intPreferencesKey(KEY_TOTAL_PREFIX + staleUri))
            }
            prefs[KEY_TRACKED_URIS] = json.encodeToString(ListSerializer(String.serializer()), tracked.take(MAX_TRACKED_URIS))
        }
    }

    fun getTotalPages(uri: Uri): Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[intPreferencesKey(KEY_TOTAL_PREFIX + uri.toString())] ?: 0
    }

    suspend fun saveTotalPages(uri: Uri, total: Int) {
        context.dataStore.edit { prefs ->
            prefs[intPreferencesKey(KEY_TOTAL_PREFIX + uri.toString())] = total
        }
    }

    /**
     * 返回所有已记录阅读进度的漫画：uri -> (progress, total)。
     * 用于书架计算阅读状态（未读/阅读中/已读）。
     */
    fun getAllProgress(): Flow<Map<String, Pair<Int, Int>>> = context.dataStore.data.map { prefs ->
        val tracked = decodeTrackedUris(prefs[KEY_TRACKED_URIS])
        val result = mutableMapOf<String, Pair<Int, Int>>()
        for (uriStr in tracked) {
            val progress = prefs[intPreferencesKey(KEY_PROGRESS_PREFIX + uriStr)] ?: 0
            val total = prefs[intPreferencesKey(KEY_TOTAL_PREFIX + uriStr)] ?: 0
            result[uriStr] = Pair(progress, total)
        }
        result
    }

    /**
     * 返回所有已记录阅读进度的漫画及其最后阅读时间：uri -> timestamp。
     * 用于书架「最近阅读」排序。
     */
    fun getRecentReads(): Flow<Map<String, Long>> = context.dataStore.data.map { prefs ->
        val tracked = decodeTrackedUris(prefs[KEY_TRACKED_URIS])
        val result = mutableMapOf<String, Long>()
        for (uriStr in tracked) {
            val progress = prefs[intPreferencesKey(KEY_PROGRESS_PREFIX + uriStr)] ?: 0
            if (progress > 0) {
                val time = prefs[longPreferencesKey(KEY_PROGRESS_TIME_PREFIX + uriStr)] ?: 0L
                result[uriStr] = time
            }
        }
        result
    }

    /** 标记为已读：进度设为最后一页 */
    suspend fun markAsRead(uri: Uri, totalPages: Int) {
        saveProgress(uri, (totalPages - 1).coerceAtLeast(0))
    }

    /** 标记为未读：进度归零 */
    suspend fun markAsUnread(uri: Uri) {
        saveProgress(uri, 0)
    }

    fun getBookshelfRoot(): Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_BOOKSHELF_ROOT]
    }

    suspend fun saveBookshelfRoot(uriString: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BOOKSHELF_ROOT] = uriString
        }
    }

    fun getBookmarks(): Flow<Map<String, List<Int>>> = context.dataStore.data.map { prefs ->
        decodeBookmarks(prefs[KEY_BOOKMARKS])
    }

    suspend fun addBookmark(uri: Uri, page: Int) {
        context.dataStore.edit { prefs ->
            val map = decodeBookmarks(prefs[KEY_BOOKMARKS]).toMutableMap()

            val key = uri.toString()
            val list = (map[key] ?: emptyList()).toMutableSet().apply { add(page) }.toSortedSet()
            map[key] = list.toList()

            prefs[KEY_BOOKMARKS] = encodeBookmarks(map)
        }
    }

    suspend fun removeBookmark(uri: Uri, page: Int) {
        context.dataStore.edit { prefs ->
            val map = decodeBookmarks(prefs[KEY_BOOKMARKS]).toMutableMap()

            val key = uri.toString()
            val list = (map[key] ?: return@edit).toMutableList().apply { remove(page) }
            if (list.isEmpty()) {
                map.remove(key)
            } else {
                map[key] = list.sorted()
            }

            prefs[KEY_BOOKMARKS] = encodeBookmarks(map)
        }
    }

    private fun decodeTrackedUris(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(String.serializer()), raw) }
            .getOrElse { raw.split("|").filter(String::isNotEmpty) }
    }

    private fun decodeBookmarks(raw: String?): Map<String, List<Int>> {
        if (raw.isNullOrBlank()) return emptyMap()
        val serializer = MapSerializer(String.serializer(), ListSerializer(Int.serializer()))
        return runCatching { json.decodeFromString(serializer, raw) }.getOrElse {
            raw.split(";").mapNotNull { entry ->
                val separator = entry.lastIndexOf(':')
                if (separator <= 0) return@mapNotNull null
                val pages = entry.substring(separator + 1).split(',').mapNotNull(String::toIntOrNull)
                entry.substring(0, separator).takeIf { pages.isNotEmpty() }?.let { it to pages }
            }.toMap()
        }
    }

    private fun encodeBookmarks(value: Map<String, List<Int>>): String = json.encodeToString(
        MapSerializer(String.serializer(), ListSerializer(Int.serializer())), value
    )

    fun getFavorites(): Flow<Set<String>> = context.dataStore.data.map { prefs ->
        val raw = prefs[KEY_FAVORITES] ?: return@map emptySet()
        raw.split("|").filter { it.isNotEmpty() }.toSet()
    }

    suspend fun toggleFavorite(uri: Uri) {
        context.dataStore.edit { prefs ->
            val raw = prefs[KEY_FAVORITES] ?: ""
            val set = raw.split("|").filter { it.isNotEmpty() }.toMutableSet()
            val key = uri.toString()
            if (set.contains(key)) set.remove(key) else set.add(key)
            prefs[KEY_FAVORITES] = set.joinToString("|")
        }
    }

    // ---------- 分类管理 ----------

    /**
     * 获取所有分类名称列表。
     * 分类存储格式: "分类1|分类2|分类3"
     */
    fun getCategories(): Flow<List<String>> = context.dataStore.data.map { prefs ->
        val raw = prefs[KEY_CATEGORIES] ?: return@map emptyList()
        raw.split("|").filter { it.isNotEmpty() }
    }

    suspend fun addCategory(name: String) {
        context.dataStore.edit { prefs ->
            val raw = prefs[KEY_CATEGORIES] ?: ""
            val list = raw.split("|").filter { it.isNotEmpty() }.toMutableList()
            if (!list.contains(name)) list.add(name)
            prefs[KEY_CATEGORIES] = list.joinToString("|")
        }
    }

    suspend fun removeCategory(name: String) {
        context.dataStore.edit { prefs ->
            val raw = prefs[KEY_CATEGORIES] ?: ""
            val list = raw.split("|").filter { it.isNotEmpty() && it != name }
            prefs[KEY_CATEGORIES] = list.joinToString("|")
            // 同时清除该分类下的所有漫画关联
            val catRaw = prefs[KEY_COMIC_CATEGORIES] ?: ""
            val map = parseComicCategoryMap(catRaw)
            map.values.forEach { it.remove(name) }
            prefs[KEY_COMIC_CATEGORIES] = serializeComicCategoryMap(map)
        }
    }

    /**
     * 获取漫画所属分类集合：uri -> Set<分类名>
     */
    fun getComicCategories(): Flow<Map<String, Set<String>>> = context.dataStore.data.map { prefs ->
        val raw = prefs[KEY_COMIC_CATEGORIES] ?: return@map emptyMap()
        parseComicCategoryMap(raw)
    }

    /**
     * 将漫画加入/移出某分类
     */
    suspend fun toggleComicCategory(uri: Uri, category: String) {
        context.dataStore.edit { prefs ->
            val raw = prefs[KEY_COMIC_CATEGORIES] ?: ""
            val map = parseComicCategoryMap(raw)
            val key = uri.toString()
            val cats = map.getOrPut(key) { mutableSetOf() }
            if (cats.contains(category)) cats.remove(category) else cats.add(category)
            prefs[KEY_COMIC_CATEGORIES] = serializeComicCategoryMap(map)
        }
    }

    private fun parseComicCategoryMap(raw: String): MutableMap<String, MutableSet<String>> {
        val result = mutableMapOf<String, MutableSet<String>>()
        raw.split(";").forEach { entry ->
            val parts = entry.split("::")
            if (parts.size == 2) {
                val uri = parts[0]
                val cats = parts[1].split(",").filter { it.isNotEmpty() }.toMutableSet()
                result[uri] = cats
            }
        }
        return result
    }

    private fun serializeComicCategoryMap(map: Map<String, Set<String>>): String {
        return map.entries
            .filter { it.value.isNotEmpty() }
            .joinToString(";") { (uri, cats) -> "$uri::${cats.joinToString(",")}" }
    }

    fun getPermissionAcknowledged(): Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_PERMISSION_ACK] ?: false
    }

    suspend fun setPermissionAcknowledged(value: Boolean) {
        context.dataStore.edit { it[KEY_PERMISSION_ACK] = value }
    }

    fun getHelpDontShow(): Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_HELP_DONT_SHOW] ?: false
    }

    suspend fun setHelpDontShow(value: Boolean) {
        context.dataStore.edit { it[KEY_HELP_DONT_SHOW] = value }
    }

    // ---------- 漫画评分、短评、标签 ----------

    fun getComicExtras(uri: Uri): Flow<ComicExtras> = context.dataStore.data.map { prefs ->
        parseComicExtras(prefs[stringPreferencesKey(KEY_COMIC_EXTRAS_PREFIX + uri.toString())])
    }

    fun getAllComicExtras(): Flow<Map<String, ComicExtras>> = context.dataStore.data.map { prefs ->
        prefs.asMap().mapNotNull { (key, value) ->
            val keyName = key.name
            if (keyName.startsWith(KEY_COMIC_EXTRAS_PREFIX) && value is String) {
                val uriString = keyName.removePrefix(KEY_COMIC_EXTRAS_PREFIX)
                uriString to parseComicExtras(value)
            } else null
        }.toMap()
    }

    suspend fun saveComicExtras(uri: Uri, extras: ComicExtras) {
        context.dataStore.edit { prefs ->
            prefs[stringPreferencesKey(KEY_COMIC_EXTRAS_PREFIX + uri.toString())] = json.encodeToString(extras)
        }
    }

    private fun parseComicExtras(raw: String?): ComicExtras {
        if (raw.isNullOrBlank()) return ComicExtras()
        return runCatching { json.decodeFromString<ComicExtras>(raw) }.getOrDefault(ComicExtras())
    }

    // ---------- 书单 ----------

    fun getReadingLists(): Flow<List<ReadingList>> = context.dataStore.data.map { prefs ->
        parseReadingLists(prefs[KEY_READING_LISTS])
    }

    suspend fun saveReadingLists(lists: List<ReadingList>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_READING_LISTS] = json.encodeToString(lists)
        }
    }

    suspend fun addComicToList(listId: String, comic: ComicEntry) {
        val lists = getReadingLists().first().toMutableList()
        val index = lists.indexOfFirst { it.id == listId }
        if (index < 0) return
        val list = lists[index]
        val item = ReadingListItem(uriString = comic.uri.toString(), title = comic.title)
        if (list.items.any { it.uriString == item.uriString }) return
        lists[index] = list.copy(items = list.items + item)
        saveReadingLists(lists)
    }

    private fun parseReadingLists(raw: String?): List<ReadingList> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<ReadingList>>(raw) }.getOrDefault(emptyList())
    }

    // ---------- 阅读统计 ----------

    fun getReadingStats(): Flow<ReadingStats> = context.dataStore.data.map { prefs ->
        parseReadingStats(prefs[KEY_READING_STATS])
    }

    suspend fun updateReadingStats(pagesDelta: Int = 0, comicFinished: Boolean = false) {
        context.dataStore.edit { prefs ->
            val stats = parseReadingStats(prefs[KEY_READING_STATS])
            val now = System.currentTimeMillis()
            val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(now))
            val newDates = stats.dailyReadDates + dateStr
            val isNightOwl = runCatching {
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
                val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                hour in 2..4
            }.getOrDefault(false)
            prefs[KEY_READING_STATS] = json.encodeToString(
                stats.copy(
                    totalPagesRead = stats.totalPagesRead + pagesDelta,
                    comicsFinished = stats.comicsFinished + if (comicFinished) 1 else 0,
                    lastReadAt = now,
                    dailyReadDates = newDates,
                    nightOwlUnlocked = stats.nightOwlUnlocked || isNightOwl
                )
            )
        }
    }

    private fun parseReadingStats(raw: String?): ReadingStats {
        if (raw.isNullOrBlank()) return ReadingStats()
        return runCatching { json.decodeFromString<ReadingStats>(raw) }.getOrDefault(ReadingStats())
    }
}

enum class ScrollMode {
    PAGE,       // 单/双页模式
    VERTICAL,   // 垂直滚动（条漫）
    HORIZONTAL  // 水平滚动
}

enum class ZoomMode {
    FREE,        // 自由缩放（默认）
    FIT_WIDTH,   // 适合宽度
    FIT_HEIGHT,  // 适合高度
    FIT_SCREEN   // 适合屏幕
}

data class ReaderSettings(
    val dualPage: Boolean = false,
    val rtl: Boolean = true,
    val immersive: Boolean = true,
    val backgroundColor: String = "#FF000000",
    val autoCrop: Boolean = false,
    val brightness: Float = 1f,
    val contrast: Float = 1f,
    val rotation: Int = 0,
    val dualPageSpacing: Int = 0,
    val scrollMode: ScrollMode = ScrollMode.PAGE,
    val grayscale: Boolean = false,
    val sharpen: Boolean = false,
    val denoise: Boolean = false,
    val dualPageOffset: Int = 0,
    val zoomMode: ZoomMode = ZoomMode.FREE,
    val mirror: Boolean = false,
    val pageShadow: Boolean = false,
    val autoPageInterval: Int = 0,
    val gamma: Float = 1f,
    val dualPageStartOne: Boolean = false,
    val saturation: Float = 1f,
    val nightMode: Boolean = false,
    val eyeCare: Boolean = false,
    val volumeKeyNav: Boolean = false,
    val pageAnimation: PageAnimation = PageAnimation.NONE,
    val randomAnimation: Boolean = false,       // 每次翻页随机一种动效
    val enableZoom: Boolean = false,
    val sharpenStrength: Float = 1f,
    val denoiseStrength: Float = 1f,
    val tapZoneSize: TapZoneSize = TapZoneSize.MEDIUM,
    val scaleFilter: ScaleFilter = ScaleFilter.BILINEAR,
    val magnifierEnabled: Boolean = false,
    val backgroundTexture: BackgroundTexture = BackgroundTexture.NONE,
    val appTheme: AppTheme = AppTheme.INK
)

enum class PageAnimation {
    NONE,       // 无动画
    SLIDE,      // 滑动
    CURL,       // 3D翻页
    FADE,       // 淡入淡出
    PIXEL,      // 像素风（盲盒动效）
    BLINDS,     // 百叶窗
    SHATTER,    // 玻璃碎裂
    DOTS        // 漫画网点
}

enum class BackgroundTexture {
    NONE,       // 纯色
    KRAFT,      // 牛皮纸
    GLASS,      // 磨砂玻璃
    STARS,      // 星空
    WOOD        // 木纹
}

/**
 * 应用级主题皮肤预设。
 */
enum class AppTheme(
    val displayName: String,
    val isDark: Boolean,
    val seedColor: Long,
    val readerBackground: String,
    val readerTexture: BackgroundTexture
) {
    INK("经典黑", true, 0xFF5C7A94, "#FF121212", BackgroundTexture.NONE),
    KRAFT("牛皮纸", false, 0xFF8D6E63, "#FFF5E6C8", BackgroundTexture.KRAFT),
    CYBER("赛博朋克", true, 0xFF00BCD4, "#FF0A0A14", BackgroundTexture.GLASS),
    EYE_CARE("护眼绿", false, 0xFF4CAF50, "#FFE8F5E9", BackgroundTexture.NONE),
    WINE("酒红幕", true, 0xFF880E4F, "#FF1A0A10", BackgroundTexture.NONE)
}

enum class TapZoneSize {
    SMALL,      // 小点击区域（中间菜单区大）
    MEDIUM,     // 中等
    LARGE        // 大点击区域（翻页区大）
}

enum class ScaleFilter {
    BILINEAR,   // 双线性（默认，快）
    BICUBIC,    // 双三次（较平滑）
    LANCZOS3    // Lanczos3（最锐利，适合低清扫描放大）
}
