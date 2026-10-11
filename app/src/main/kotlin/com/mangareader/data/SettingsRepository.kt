package com.mangareader.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.mangareader.R
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json


/**
 * Facade Repository：保留阅读设置、备份/恢复等核心能力，
 * 其余职责委托给各单一职责 Repository（Bookmark / Bookshelf / ReadingHistory / ComicExtras / ReadingProgress）。
 */
class SettingsRepository(private val context: Context) {

    companion object {
        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }

        private val KEY_DUAL_PAGE = booleanPreferencesKey("dual_page")
        private val KEY_RTL = booleanPreferencesKey("rtl")
        private val KEY_IMMERSIVE = booleanPreferencesKey("immersive")
        private val KEY_BG_COLOR = stringPreferencesKey("bg_color")
        private val KEY_AUTO_CROP = booleanPreferencesKey("auto_crop")
        private val KEY_CROP_MARGIN_TOP = intPreferencesKey("crop_margin_top")
        private val KEY_CROP_MARGIN_BOTTOM = intPreferencesKey("crop_margin_bottom")
        private val KEY_CROP_MARGIN_LEFT = intPreferencesKey("crop_margin_left")
        private val KEY_CROP_MARGIN_RIGHT = intPreferencesKey("crop_margin_right")
        private val KEY_BRIGHTNESS = floatPreferencesKey("brightness")
        private val KEY_CONTRAST = floatPreferencesKey("contrast")
        private val KEY_ROTATION = intPreferencesKey("rotation")
        private val KEY_DUAL_PAGE_SPACING = intPreferencesKey("dual_page_spacing")
        private val KEY_AUTO_DUAL_PAGE_LANDSCAPE = booleanPreferencesKey("auto_dual_page_landscape")
        private val KEY_SCROLL_MODE = stringPreferencesKey("scroll_mode")
        private val KEY_GRAYSCALE = booleanPreferencesKey("grayscale")
        private val KEY_SHARPEN = booleanPreferencesKey("sharpen")
        private val KEY_DENOISE = booleanPreferencesKey("denoise")
        private val KEY_DUAL_PAGE_OFFSET = intPreferencesKey("dual_page_offset")
        private val KEY_ZOOM_MODE = stringPreferencesKey("zoom_mode")
        private val KEY_MIRROR = booleanPreferencesKey("mirror")
        private val KEY_PAGE_SHADOW = booleanPreferencesKey("page_shadow")
        private val KEY_AUTO_PAGE_INTERVAL = intPreferencesKey("auto_page_interval")
        private val KEY_GAMMA = floatPreferencesKey("gamma")
        private val KEY_DUAL_PAGE_START_ONE = booleanPreferencesKey("dual_page_start_one")
        private val KEY_PERMISSION_ACK = booleanPreferencesKey("permission_acknowledged")
        private val KEY_HELP_DONT_SHOW = booleanPreferencesKey("help_dont_show")
        private val KEY_READER_EXIT_CONFIRM_DISABLED = booleanPreferencesKey("reader_exit_confirm_disabled")
        private val KEY_SATURATION = floatPreferencesKey("saturation")
        private val KEY_NIGHT_MODE = booleanPreferencesKey("night_mode")
        private val KEY_EYE_CARE = booleanPreferencesKey("eye_care")
        private val KEY_VOLUME_KEY_NAV = booleanPreferencesKey("volume_key_nav")
        private val KEY_PAGE_ANIMATION = stringPreferencesKey("page_animation")
        private val KEY_PAGE_ANIM_SPEED = floatPreferencesKey("page_anim_speed")
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
        private val KEY_CUSTOM_SEED_COLOR = stringPreferencesKey("custom_seed_color")
        private val KEY_FLIP_SOUND = booleanPreferencesKey("flip_sound")
        private val KEY_FLIP_VIBRATION = booleanPreferencesKey("flip_vibration")
        private val KEY_ORIENTATION_LOCK = stringPreferencesKey("orientation_lock")
        private val KEY_GESTURE_CONFIG = stringPreferencesKey("gesture_config")
        private val KEY_AUTO_BRIGHTNESS = booleanPreferencesKey("auto_brightness")
        private val KEY_TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        private val KEY_TTS_SPEED = floatPreferencesKey("tts_speed")
        private val KEY_TTS_PITCH = floatPreferencesKey("tts_pitch")
        private val KEY_THEME_AUTO_MODE = stringPreferencesKey("theme_auto_mode")
        private val KEY_ANTI_MISTOUCH = booleanPreferencesKey("anti_mistouch")
        private val KEY_READER_STATUS_BAR = booleanPreferencesKey("reader_status_bar")
        private val KEY_THUMBNAIL_STRIP = booleanPreferencesKey("thumbnail_strip")
        private val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")

        private val KEY_PROGRESS_PREFIX = "progress_"
        private val KEY_PROGRESS_TIME_PREFIX = "progress_time_"
        private val KEY_TOTAL_PREFIX = "total_"

        private const val BACKUP_VERSION = 2
        private const val MIN_SUPPORTED_BACKUP_VERSION = 1

        private const val READER_SETTINGS_VERSION = 2
        private val KEY_READER_SETTINGS_VERSION = intPreferencesKey("reader_settings_version")

        private val json = Json { ignoreUnknownKeys = true }
    }

    private val bookmarkRepository = BookmarkRepository.getInstance(context)
    private val bookshelfRepository = BookshelfRepository.getInstance(context)
    private val readingHistoryRepository = ReadingHistoryRepository.getInstance(context)
    private val comicExtrasRepository = ComicExtrasRepository.getInstance(context)
    private val readingProgressRepository = ReadingProgressRepository.getInstance(context)

    @Volatile
    private var saveJob: Job? = null
    private val saveJobLock = Mutex()
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val statsRepository = ReadingStatsRepository.getInstance(context)

    // ==================== 阅读设置（核心保留） ====================

    val readerSettings: Flow<ReaderSettings> = context.userDataStore.data
        .transform { prefs ->
            val settings = parseReaderSettings(prefs)
            emit(settings)
            if ((prefs[KEY_READER_SETTINGS_VERSION] ?: 0) < READER_SETTINGS_VERSION) {
                val (correctedRtl, correctedGesture) = when {
                    settings.rtl -> true to GestureConfig.PRESET_MANGA
                    settings.gestureConfig.presetKey() == "simple" -> false to GestureConfig.PRESET_SIMPLE
                    else -> false to GestureConfig.PRESET_COMIC
                }
                context.userDataStore.edit { p ->
                    p[KEY_RTL] = correctedRtl
                    p[KEY_GESTURE_CONFIG] = GestureConfig.json.encodeToString(correctedGesture)
                    p[KEY_READER_SETTINGS_VERSION] = READER_SETTINGS_VERSION
                }
            }
        }
        .flowOn(Dispatchers.IO)

    private fun migrateGestureConfig(config: GestureConfig): GestureConfig {
        val isOldMangaPreset = config.presetName == "日漫模式" &&
                config.tapLeft == GestureConfig.GestureAction.NEXT_PAGE &&
                config.tapRight == GestureConfig.GestureAction.PREV_PAGE &&
                config.swipeLeft == GestureConfig.GestureAction.NEXT_PAGE &&
                config.swipeRight == GestureConfig.GestureAction.PREV_PAGE
        return if (isOldMangaPreset) GestureConfig.PRESET_MANGA else config
    }

    private fun parseReaderSettings(prefs: Preferences): ReaderSettings {
        return ReaderSettings(
            dualPage = prefs[KEY_DUAL_PAGE] ?: false,
            autoDualPageInLandscape = prefs[KEY_AUTO_DUAL_PAGE_LANDSCAPE] ?: false,
            rtl = prefs[KEY_RTL] ?: false,
            immersive = prefs[KEY_IMMERSIVE] ?: true,
            backgroundColor = prefs[KEY_BG_COLOR] ?: "#FF000000",
            autoCrop = prefs[KEY_AUTO_CROP] ?: false,
            cropMarginTop = prefs[KEY_CROP_MARGIN_TOP] ?: 0,
            cropMarginBottom = prefs[KEY_CROP_MARGIN_BOTTOM] ?: 0,
            cropMarginLeft = prefs[KEY_CROP_MARGIN_LEFT] ?: 0,
            cropMarginRight = prefs[KEY_CROP_MARGIN_RIGHT] ?: 0,
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
            pageAnimation = runCatching {
                val name = prefs[KEY_PAGE_ANIMATION] ?: "NONE"
                if (name == "CURL") PageAnimation.NONE else PageAnimation.valueOf(name)
            }.getOrDefault(PageAnimation.NONE),
            randomAnimation = prefs[KEY_RANDOM_ANIMATION] ?: false,
            pageAnimSpeed = prefs[KEY_PAGE_ANIM_SPEED] ?: 1.0f,
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
                .getOrDefault(AppTheme.INK),
            customSeedColor = prefs[KEY_CUSTOM_SEED_COLOR] ?: "#FF5C7A94",
            flipSoundEnabled = prefs[KEY_FLIP_SOUND] ?: false,
            flipVibrationEnabled = prefs[KEY_FLIP_VIBRATION] ?: true,
            orientationLock = runCatching { OrientationLock.valueOf(prefs[KEY_ORIENTATION_LOCK] ?: "FOLLOW_SYSTEM") }
                .getOrDefault(OrientationLock.FOLLOW_SYSTEM),
            gestureConfig = runCatching {
                val loaded = GestureConfig.json.decodeFromString<GestureConfig>(prefs[KEY_GESTURE_CONFIG] ?: "")
                migrateGestureConfig(loaded)
            }.getOrDefault(
                if (runCatching { prefs[KEY_RTL] ?: false }.getOrDefault(false))
                    GestureConfig.PRESET_MANGA else GestureConfig.PRESET_COMIC
            ),
            autoBrightness = prefs[KEY_AUTO_BRIGHTNESS] ?: false,
            ttsEnabled = prefs[KEY_TTS_ENABLED] ?: false,
            ttsSpeed = prefs[KEY_TTS_SPEED] ?: 1.0f,
            ttsPitch = prefs[KEY_TTS_PITCH] ?: 1.0f,
            themeAutoMode = runCatching { ThemeAutoMode.valueOf(prefs[KEY_THEME_AUTO_MODE] ?: "OFF") }
                .getOrDefault(ThemeAutoMode.OFF),
            antiMistouch = prefs[KEY_ANTI_MISTOUCH] ?: false,
            readerStatusBar = prefs[KEY_READER_STATUS_BAR] ?: false,
            thumbnailStripEnabled = prefs[KEY_THUMBNAIL_STRIP] ?: false,
            keepScreenOn = prefs[KEY_KEEP_SCREEN_ON] ?: true
        )
    }

    suspend fun saveReaderSettings(settings: ReaderSettings) {
        context.userDataStore.edit { prefs ->
            saveReaderSettingsToPreferences(prefs, settings)
        }
    }

    suspend fun resetReaderSettings() {
        context.userDataStore.edit { prefs ->
            val current = parseReaderSettings(prefs)
            val reset = ReaderSettings(
                appTheme = current.appTheme,
                customSeedColor = current.customSeedColor,
                themeAutoMode = current.themeAutoMode
            )
            saveReaderSettingsToPreferences(prefs, reset)
        }
    }

    suspend fun resetImageSettings() {
        context.userDataStore.edit { prefs ->
            val current = parseReaderSettings(prefs)
            val reset = current.copy(
                autoCrop = false,
                cropMarginTop = 0,
                cropMarginBottom = 0,
                cropMarginLeft = 0,
                cropMarginRight = 0,
                brightness = 1f,
                contrast = 1f,
                rotation = 0,
                grayscale = false,
                sharpen = false,
                denoise = false,
                mirror = false,
                gamma = 1f,
                saturation = 1f,
                nightMode = false,
                eyeCare = false,
                scaleFilter = ScaleFilter.BILINEAR,
                sharpenStrength = 1f,
                denoiseStrength = 1f,
                backgroundColor = current.appTheme.readerBackground,
                backgroundTexture = current.appTheme.readerTexture
            )
            saveReaderSettingsToPreferences(prefs, reset)
        }
    }

    suspend fun resetGestureSettings() {
        context.userDataStore.edit { prefs ->
            val current = parseReaderSettings(prefs)
            val preset = if (current.rtl) GestureConfig.PRESET_MANGA else GestureConfig.PRESET_COMIC
            val reset = current.copy(gestureConfig = preset)
            saveReaderSettingsToPreferences(prefs, reset)
        }
    }

    private fun saveReaderSettingsToPreferences(prefs: MutablePreferences, settings: ReaderSettings) {
        prefs[KEY_DUAL_PAGE] = settings.dualPage
        prefs[KEY_AUTO_DUAL_PAGE_LANDSCAPE] = settings.autoDualPageInLandscape
        prefs[KEY_RTL] = settings.rtl
        prefs[KEY_IMMERSIVE] = settings.immersive
        prefs[KEY_BG_COLOR] = settings.backgroundColor
        prefs[KEY_AUTO_CROP] = settings.autoCrop
        prefs[KEY_CROP_MARGIN_TOP] = settings.cropMarginTop
        prefs[KEY_CROP_MARGIN_BOTTOM] = settings.cropMarginBottom
        prefs[KEY_CROP_MARGIN_LEFT] = settings.cropMarginLeft
        prefs[KEY_CROP_MARGIN_RIGHT] = settings.cropMarginRight
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
        prefs[KEY_PAGE_ANIM_SPEED] = settings.pageAnimSpeed
        prefs[KEY_BG_TEXTURE] = settings.backgroundTexture.name
        prefs[KEY_ENABLE_ZOOM] = settings.enableZoom
        prefs[KEY_SHARPEN_STRENGTH] = settings.sharpenStrength
        prefs[KEY_DENOISE_STRENGTH] = settings.denoiseStrength
        prefs[KEY_TAP_ZONE_SIZE] = settings.tapZoneSize.name
        prefs[KEY_SCALE_FILTER] = settings.scaleFilter.name
        prefs[KEY_MAGNIFIER] = settings.magnifierEnabled
        prefs[KEY_APP_THEME] = settings.appTheme.name
        prefs[KEY_CUSTOM_SEED_COLOR] = settings.customSeedColor
        prefs[KEY_FLIP_SOUND] = settings.flipSoundEnabled
        prefs[KEY_FLIP_VIBRATION] = settings.flipVibrationEnabled
        prefs[KEY_ORIENTATION_LOCK] = settings.orientationLock.name
        prefs[KEY_GESTURE_CONFIG] = GestureConfig.json.encodeToString(settings.gestureConfig)
        prefs[KEY_AUTO_BRIGHTNESS] = settings.autoBrightness
        prefs[KEY_TTS_ENABLED] = settings.ttsEnabled
        prefs[KEY_TTS_SPEED] = settings.ttsSpeed
        prefs[KEY_TTS_PITCH] = settings.ttsPitch
        prefs[KEY_THEME_AUTO_MODE] = settings.themeAutoMode.name
        prefs[KEY_ANTI_MISTOUCH] = settings.antiMistouch
        prefs[KEY_READER_STATUS_BAR] = settings.readerStatusBar
        prefs[KEY_THUMBNAIL_STRIP] = settings.thumbnailStripEnabled
        prefs[KEY_KEEP_SCREEN_ON] = settings.keepScreenOn
        prefs[KEY_READER_SETTINGS_VERSION] = READER_SETTINGS_VERSION
    }

    fun saveAsync(settings: ReaderSettings) {
        repositoryScope.launch {
            saveJobLock.withLock {
                saveJob?.cancel()
                saveJob = repositoryScope.launch {
                    try {
                        delay(300)
                        context.userDataStore.edit { prefs ->
                            if (!currentCoroutineContext().isActive) return@edit
                            saveReaderSettingsToPreferences(prefs, settings)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("SettingsRepository", "保存阅读设置失败", e)
                    }
                }
            }
        }
    }

    suspend fun flushSave(settings: ReaderSettings) {
        saveJobLock.withLock {
            val current = saveJob
            saveJob?.cancel()
            saveJob = null
            current?.join()
            context.userDataStore.edit { prefs ->
                saveReaderSettingsToPreferences(prefs, settings)
            }
        }
    }

    // ==================== 权限 / 帮助提示 ====================

    fun getPermissionAcknowledged(): Flow<Boolean> = context.userDataStore.data.map { prefs ->
        prefs[KEY_PERMISSION_ACK] ?: false
    }.flowOn(Dispatchers.IO)

    suspend fun setPermissionAcknowledged(value: Boolean) {
        context.userDataStore.edit { it[KEY_PERMISSION_ACK] = value }
    }

    fun getHelpDontShow(): Flow<Boolean> = context.userDataStore.data.map { prefs ->
        prefs[KEY_HELP_DONT_SHOW] ?: false
    }.flowOn(Dispatchers.IO)

    suspend fun setHelpDontShow(value: Boolean) {
        context.userDataStore.edit { it[KEY_HELP_DONT_SHOW] = value }
    }

    fun getReaderExitConfirmDisabled(): Flow<Boolean> = context.userDataStore.data.map { prefs ->
        prefs[KEY_READER_EXIT_CONFIRM_DISABLED] ?: false
    }.flowOn(Dispatchers.IO)

    suspend fun setReaderExitConfirmDisabled(value: Boolean) {
        context.userDataStore.edit { it[KEY_READER_EXIT_CONFIRM_DISABLED] = value }
    }

    // ==================== 阅读统计（委托） ====================

    fun getReadingStats(): Flow<ReadingStats> = statsRepository.getReadingStats()

    suspend fun updateReadingStats(
        pagesDelta: Int = 0,
        comicFinished: Boolean = false,
        readingMinutes: Int = 0
    ) = statsRepository.updateReadingStats(pagesDelta, comicFinished, readingMinutes)

    suspend fun startReadingSession(uri: Uri) = statsRepository.startReadingSession(uri)

    suspend fun endReadingSession(expectedUri: Uri? = null) = statsRepository.endReadingSession(expectedUri)

    suspend fun recoverReadingSession() = statsRepository.recoverReadingSession()

    fun getReadingGoals(): Flow<ReadingGoals> = statsRepository.getReadingGoals()

    suspend fun saveReadingGoals(goals: ReadingGoals) = statsRepository.saveReadingGoals(goals)

    suspend fun getGoalsAndStats(): Pair<ReadingGoals, ReadingStats> = statsRepository.getGoalsAndStats()

    fun getGoalHistory(): Flow<List<GoalHistoryEntry>> = statsRepository.getGoalHistory()

    suspend fun recordGoalAchievement(
        date: String,
        pagesAchieved: Boolean,
        minutesAchieved: Boolean
    ) = statsRepository.recordGoalAchievement(date, pagesAchieved, minutesAchieved)

    // ==================== 委托：BookmarkRepository ====================

    fun getBookmarks(): Flow<Map<String, List<Int>>> = bookmarkRepository.getBookmarks()

    suspend fun addBookmark(uri: Uri, page: Int) = bookmarkRepository.addBookmark(uri, page)

    suspend fun removeBookmark(uri: Uri, page: Int) = bookmarkRepository.removeBookmark(uri, page)

    suspend fun saveBookmarks(uri: Uri, pages: List<Int>) = bookmarkRepository.saveBookmarks(uri, pages)

    suspend fun mergeBookmarks(uriToPages: Map<String, List<Int>>) = bookmarkRepository.mergeBookmarks(uriToPages)

    fun getFavorites(): Flow<Set<String>> = bookmarkRepository.getFavorites()

    suspend fun toggleFavorite(uri: Uri) = bookmarkRepository.toggleFavorite(uri)

    suspend fun addFavorites(uris: Set<String>) = bookmarkRepository.addFavorites(uris)

    fun getCategories(): Flow<List<String>> = bookmarkRepository.getCategories()

    suspend fun addCategory(name: String) = bookmarkRepository.addCategory(name)

    suspend fun removeCategory(name: String) = bookmarkRepository.removeCategory(name)

    fun getComicCategories(): Flow<Map<String, Set<String>>> = bookmarkRepository.getComicCategories()

    suspend fun toggleComicCategory(uri: Uri, category: String) = bookmarkRepository.toggleComicCategory(uri, category)

    // ==================== 委托：BookshelfRepository ====================

    fun getBookshelfRoot(): Flow<String?> = bookshelfRepository.getBookshelfRoot()

    suspend fun saveBookshelfRoot(uriString: String) = bookshelfRepository.saveBookshelfRoot(uriString)

    fun getBookshelfUris(): Flow<List<String>> = bookshelfRepository.getBookshelfUris()

    suspend fun saveBookshelfUris(uris: List<String>) = bookshelfRepository.saveBookshelfUris(uris)

    suspend fun addBookshelfUri(uriString: String) = bookshelfRepository.addBookshelfUri(uriString)

    suspend fun removeBookshelfUri(uriString: String) = bookshelfRepository.removeBookshelfUri(uriString)

    fun getCurrentBookshelfIndex(): Flow<Int> = bookshelfRepository.getCurrentBookshelfIndex()

    suspend fun saveCurrentBookshelfIndex(index: Int) = bookshelfRepository.saveCurrentBookshelfIndex(index)

    fun getBookshelfViewMode(): Flow<String> = bookshelfRepository.getBookshelfViewMode()

    suspend fun saveBookshelfViewMode(mode: String) = bookshelfRepository.saveBookshelfViewMode(mode)

    fun getBookshelfFilter(): Flow<String> = bookshelfRepository.getBookshelfFilter()

    suspend fun saveBookshelfFilter(filter: String) = bookshelfRepository.saveBookshelfFilter(filter)

    fun getBookshelfSort(): Flow<String> = bookshelfRepository.getBookshelfSort()

    suspend fun saveBookshelfSort(sort: String) = bookshelfRepository.saveBookshelfSort(sort)

    fun getBookshelfSelectedCategory(): Flow<String?> = bookshelfRepository.getBookshelfSelectedCategory()

    suspend fun saveBookshelfSelectedCategory(category: String?) = bookshelfRepository.saveBookshelfSelectedCategory(category)

    fun getBookshelfSearchQuery(): Flow<String> = bookshelfRepository.getBookshelfSearchQuery()

    suspend fun saveBookshelfSearchQuery(query: String) = bookshelfRepository.saveBookshelfSearchQuery(query)

    fun isBookshelfSearching(): Flow<Boolean> = bookshelfRepository.isBookshelfSearching()

    suspend fun saveBookshelfSearching(searching: Boolean) = bookshelfRepository.saveBookshelfSearching(searching)

    fun getBookshelfSearchHistory(): Flow<List<String>> = bookshelfRepository.getBookshelfSearchHistory()

    suspend fun addBookshelfSearchHistory(query: String) = bookshelfRepository.addBookshelfSearchHistory(query)

    suspend fun clearBookshelfSearchHistory() = bookshelfRepository.clearBookshelfSearchHistory()

    fun getBookshelfScanState(rootUriString: String): Flow<BookshelfScanState> =
        bookshelfRepository.getBookshelfScanState(rootUriString)

    suspend fun saveBookshelfScanState(rootUriString: String, state: BookshelfScanState) =
        bookshelfRepository.saveBookshelfScanState(rootUriString, state)

    suspend fun markBookshelfFilesSeen(rootUriString: String, currentFiles: Set<String>) =
        bookshelfRepository.markBookshelfFilesSeen(rootUriString, currentFiles)

    // ==================== 委托：ReadingHistoryRepository ====================

    fun getReadingHistory(): Flow<List<ReadingHistoryEntry>> = readingHistoryRepository.getReadingHistory()

    suspend fun addReadingHistory(entry: ReadingHistoryEntry) = readingHistoryRepository.addReadingHistory(entry)

    suspend fun clearReadingHistory(uriString: String? = null) = readingHistoryRepository.clearReadingHistory(uriString)

    fun getRecentReads(): Flow<Map<String, Long>> = readingHistoryRepository.getRecentReads()

    suspend fun markAsRead(uri: Uri, totalPages: Int) = readingHistoryRepository.markAsRead(uri, totalPages)

    suspend fun markAsUnread(uri: Uri) = readingHistoryRepository.markAsUnread(uri)

    // ==================== 委托：ComicExtrasRepository ====================

    fun getComicExtras(uri: Uri): Flow<ComicExtras> = comicExtrasRepository.getComicExtras(uri)

    fun getAllComicExtras(): Flow<Map<String, ComicExtras>> = comicExtrasRepository.getAllComicExtras()

    suspend fun saveComicExtras(uri: Uri, extras: ComicExtras) = comicExtrasRepository.saveComicExtras(uri, extras)

    fun getReadingLists(): Flow<List<ReadingList>> = comicExtrasRepository.getReadingLists()

    suspend fun saveReadingLists(lists: List<ReadingList>) = comicExtrasRepository.saveReadingLists(lists)

    suspend fun addComicToList(listId: String, comic: ComicEntry) = comicExtrasRepository.addComicToList(listId, comic)

    // ==================== 委托：ReadingProgressRepository ====================

    fun getProgress(uri: Uri): Flow<Int> = readingProgressRepository.getProgress(uri)

    fun getProgress(uri: Uri, chapterTitle: String?): Flow<Int> = readingProgressRepository.getProgress(uri, chapterTitle)

    suspend fun saveProgress(uri: Uri, page: Int) = readingProgressRepository.saveProgress(uri, page)

    suspend fun saveProgress(uri: Uri, chapterTitle: String?, page: Int) =
        readingProgressRepository.saveProgress(uri, chapterTitle, page)

    fun getTotalPages(uri: Uri): Flow<Int> = readingProgressRepository.getTotalPages(uri)

    suspend fun saveTotalPages(uri: Uri, total: Int) = readingProgressRepository.saveTotalPages(uri, total)

    suspend fun saveReadingProgress(
        uri: Uri,
        chapterTitle: String?,
        chapterPage: Int,
        overallPage: Int,
        totalPages: Int
    ) = readingProgressRepository.saveReadingProgress(uri, chapterTitle, chapterPage, overallPage, totalPages)

    suspend fun saveProgressBatch(
        progressMap: Map<String, Int>,
        totalMap: Map<String, Int>
    ) = readingProgressRepository.saveProgressBatch(progressMap, totalMap)

    suspend fun saveProgressTimesBatch(times: Map<String, Long>) =
        readingProgressRepository.saveProgressTimesBatch(times)

    fun getAllProgress(): Flow<Map<String, Pair<Int, Int>>> = readingProgressRepository.getAllProgress()

    /**
     * 漫画文件/目录重命名后，把所有以旧 URI 为键或在 JSON 串中包含旧 URI 的
     * 用户数据（进度、进度时间、总页数、收藏、分类、书签、短评、历史、tracked 列表等）
     * 统一迁移到新 URI，避免重命名后漫画变回未读、收藏/书签/分类全部丢失。
     * 在同一个 DataStore 事务内完成，键重命名与字符串替换两种形态都覆盖。
     */
    suspend fun migrateComicUriData(oldUri: Uri, newUri: Uri) = withContext(Dispatchers.IO) {
        val old = oldUri.toString()
        val new = newUri.toString()
        if (old.isBlank() || old == new) return@withContext
        runCatching {
            context.userDataStore.edit { prefs ->
                val entries = prefs.asMap().entries.toList()
                for ((key, value) in entries) {
                    if (key.name.contains(old)) {
                        // 键名本身含 URI（progress_/progress_time_/total_ 等标量键）：改名迁移
                        val newName = key.name.replace(old, new)
                        when (value) {
                            is Int -> prefs[intPreferencesKey(newName)] = value
                            is Long -> prefs[longPreferencesKey(newName)] = value
                            is Boolean -> prefs[booleanPreferencesKey(newName)] = value
                            is Float -> prefs[floatPreferencesKey(newName)] = value
                            is String -> prefs[stringPreferencesKey(newName)] = value.replace(old, new)
                            is Set<*> -> {
                                @Suppress("UNCHECKED_CAST")
                                prefs[stringSetPreferencesKey(newName)] =
                                        (value as Set<String>).mapTo(LinkedHashSet()) { it.replace(old, new) }
                            }
                        }
                        prefs.remove(key)
                    } else when (value) {
                        // JSON 字符串中的 URI（收藏列表/分类映射/书签/短评/历史/tracked_uris）
                        is String -> if (value.contains(old)) {
                            @Suppress("UNCHECKED_CAST")
                            prefs[key as Preferences.Key<String>] = value.replace(old, new)
                        }
                        is Set<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            val oldSet = value as Set<String>
                            if (oldSet.any { it.contains(old) }) {
                                @Suppress("UNCHECKED_CAST")
                                prefs[key as Preferences.Key<Set<String>>] =
                                        oldSet.mapTo(LinkedHashSet()) { it.replace(old, new) }
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }.onFailure { Log.w("SettingsRepository", "迁移漫画关联数据失败: $old -> $new", it) }
    }

    // ==================== 本地备份与恢复 ====================

    suspend fun exportAllData(): String = withContext(Dispatchers.IO) {
        val backup = BackupData(
            version = BACKUP_VERSION,
            progress = getAllProgress().first(),
            progressTimes = getRecentReads().first(),
            bookmarks = getBookmarks().first(),
            favorites = getFavorites().first().toList(),
            categories = getCategories().first(),
            comicCategories = getComicCategories().first(),
            readingLists = getReadingLists().first(),
            readingStats = getReadingStats().first(),
            readingHistory = getReadingHistory().first(),
            comicExtras = getAllComicExtras().first(),
            readerSettings = readerSettings.first()
        )
        json.encodeToString(backup)
    }

    suspend fun importAllData(jsonString: String, overwrite: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        try {
            val backup = runCatching { json.decodeFromString<BackupData>(jsonString) }.getOrNull()
                ?: return@withContext false
            if (backup.version < MIN_SUPPORTED_BACKUP_VERSION) {
                Log.e("SettingsRepository", "备份版本 ${backup.version} 过低，不支持导入")
                return@withContext false
            }
            if (backup.version > BACKUP_VERSION) {
                Log.w("SettingsRepository", "备份版本 ${backup.version} 高于当前支持版本 ${BACKUP_VERSION}，可能无法完全兼容")
            }
            val migratedBackup = migrateBackupIfNeeded(backup)
            val existingExtras = if (overwrite) emptyMap() else getAllComicExtras().first()
            context.userDataStore.edit { prefs ->
                val existingProgress = if (overwrite) emptyMap() else parseAllProgress(prefs)
                val mergedProgress = (existingProgress.keys + migratedBackup.progress.keys).associateWith { key ->
                    val existing = existingProgress[key] ?: (0 to 0)
                    val remote = migratedBackup.progress[key] ?: (0 to 0)
                    maxOf(existing.first, remote.first) to maxOf(existing.second, remote.second)
                }
                saveAllProgressInternal(prefs, mergedProgress)

                val existingProgressTimes = if (overwrite) emptyMap() else parseProgressTimes(prefs)
                val mergedProgressTimes = (existingProgressTimes.keys + migratedBackup.progressTimes.keys).associateWith { key ->
                    maxOf(existingProgressTimes[key] ?: 0L, migratedBackup.progressTimes[key] ?: 0L)
                }
                saveProgressTimesInternal(prefs, mergedProgressTimes)

                val existingBookmarks = if (overwrite) emptyMap() else parseBookmarksMap(prefs[stringPreferencesKey("bookmarks_map")])
                val mergedBookmarks = (existingBookmarks.keys + migratedBackup.bookmarks.keys).associateWith { key ->
                    ((existingBookmarks[key] ?: emptyList()) + (migratedBackup.bookmarks[key] ?: emptyList())).distinct().sorted()
                }
                prefs[stringPreferencesKey("bookmarks_map")] = json.encodeToString(mergedBookmarks)

                val existingFavorites = if (overwrite) emptySet() else parseStringSetInternal(prefs[stringPreferencesKey("favorites_set")])
                prefs[stringPreferencesKey("favorites_set")] = json.encodeToString((existingFavorites + migratedBackup.favorites.toSet()).toList())

                val existingCategories = if (overwrite) emptyList() else parseStringListInternal(prefs[stringPreferencesKey("categories_list")])
                prefs[stringPreferencesKey("categories_list")] = json.encodeToString((existingCategories + migratedBackup.categories).distinct())

                val existingComicCategories = if (overwrite) emptyMap() else parseComicCategoriesInternal(prefs[stringPreferencesKey("comic_categories")])
                val mergedComicCategories = (existingComicCategories.keys + migratedBackup.comicCategories.keys).associateWith { key ->
                    (existingComicCategories[key] ?: emptySet()) + (migratedBackup.comicCategories[key] ?: emptySet())
                }
                prefs[stringPreferencesKey("comic_categories")] = json.encodeToString(mergedComicCategories)

                val existingLists = if (overwrite) emptyList() else parseReadingListsInternal(prefs[stringPreferencesKey("reading_lists")])
                val listMap = existingLists.associateBy { it.name }.toMutableMap()
                for (list in migratedBackup.readingLists) {
                    listMap[list.name] = list.copy(
                        items = ((listMap[list.name]?.items ?: emptyList()) + list.items).distinct()
                    )
                }
                prefs[stringPreferencesKey("reading_lists")] = json.encodeToString(listMap.values.toList())

                val existingHistory = if (overwrite) emptyList() else parseReadingHistoryInternal(prefs[stringPreferencesKey("reading_history")])
                val historyMap = existingHistory.associateBy { it.uriString }.toMutableMap()
                for (entry in migratedBackup.readingHistory) {
                    val old = historyMap[entry.uriString]
                    if (old == null || entry.lastReadAt > old.lastReadAt) {
                        historyMap[entry.uriString] = entry
                    }
                }
                val mergedHistory = historyMap.values.sortedByDescending { it.lastReadAt }.take(200)
                prefs[stringPreferencesKey("reading_history")] = json.encodeToString(mergedHistory)

                val mergedExtras = (existingExtras.keys + migratedBackup.comicExtras.keys).associateWith { key ->
                    existingExtras[key]?.mergeWith(migratedBackup.comicExtras[key]) ?: (migratedBackup.comicExtras[key] ?: ComicExtras())
                }
                for ((uri, extras) in mergedExtras) {
                    prefs[stringPreferencesKey("extras_$uri")] = json.encodeToString(extras)
                }

                if (overwrite) {
                    saveReaderSettingsToPreferences(prefs, migratedBackup.readerSettings)
                }
            }
            statsRepository.mergeReadingStats(migratedBackup.readingStats, overwrite)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SettingsRepository", "导入备份失败", e)
            false
        }
    }

    private fun migrateBackupIfNeeded(backup: BackupData): BackupData {
        var current = backup
        if (current.version < BACKUP_VERSION) {
            current = current.copy(version = BACKUP_VERSION)
        }
        return current
    }

    private fun saveAllProgressInternal(prefs: MutablePreferences, progress: Map<String, Pair<Int, Int>>) {
        val tracked = progress.keys.toMutableList()
        for ((uri, pair) in progress) {
            prefs[intPreferencesKey(KEY_PROGRESS_PREFIX + uri)] = pair.first
            prefs[intPreferencesKey(KEY_TOTAL_PREFIX + uri)] = pair.second
            prefs[longPreferencesKey(KEY_PROGRESS_TIME_PREFIX + uri)] = System.currentTimeMillis()
        }
        prefs[KEY_TRACKED_URIS] = json.encodeToString(tracked.take(MAX_TRACKED_URIS))
    }

    private fun parseAllProgress(prefs: Preferences): Map<String, Pair<Int, Int>> {
        val tracked = decodeTrackedUrisInternal(prefs[KEY_TRACKED_URIS])
        return tracked.associateWith { uri ->
            val page = prefs[intPreferencesKey(KEY_PROGRESS_PREFIX + uri)] ?: 0
            val total = prefs[intPreferencesKey(KEY_TOTAL_PREFIX + uri)] ?: 0
            page to total
        }
    }

    private fun parseProgressTimes(prefs: Preferences): Map<String, Long> {
        val tracked = decodeTrackedUrisInternal(prefs[KEY_TRACKED_URIS])
        return tracked.associateWith { uri ->
            prefs[longPreferencesKey(KEY_PROGRESS_TIME_PREFIX + uri)] ?: 0L
        }.filter { it.value > 0L }
    }

    private fun saveProgressTimesInternal(prefs: MutablePreferences, times: Map<String, Long>) {
        val tracked = decodeTrackedUrisInternal(prefs[KEY_TRACKED_URIS]).toMutableSet()
        for ((uri, time) in times) {
            prefs[longPreferencesKey(KEY_PROGRESS_TIME_PREFIX + uri)] = time
            tracked += uri
        }
        prefs[KEY_TRACKED_URIS] = json.encodeToString(tracked.take(MAX_TRACKED_URIS))
    }

    private fun decodeTrackedUrisInternal(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(String.serializer()), raw) }
            .getOrElse { raw.split("|").filter(String::isNotEmpty) }
    }

    private fun parseBookmarksMap(raw: String?): Map<String, List<Int>> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, List<Int>>>(raw) }.getOrDefault(emptyMap())
    }

    private fun parseStringSetInternal(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        val decoded = runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull()
        if (decoded != null) return decoded.toSet()
        return raw.split("|").filter { it.isNotEmpty() }.toSet()
    }

    private fun parseStringListInternal(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val decoded = runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull()
        if (decoded != null) return decoded
        return raw.split("|").filter { it.isNotEmpty() }
    }

    private fun parseComicCategoriesInternal(raw: String?): Map<String, Set<String>> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, Set<String>>>(raw) }.getOrDefault(emptyMap())
    }

    private fun parseReadingListsInternal(raw: String?): List<ReadingList> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<ReadingList>>(raw) }.getOrDefault(emptyList())
    }

    private fun parseReadingHistoryInternal(raw: String?): List<ReadingHistoryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<ReadingHistoryEntry>>(raw) }.getOrDefault(emptyList())
    }

    @kotlinx.serialization.Serializable
    private data class BackupData(
        val version: Int = 2,
        val progress: Map<String, Pair<Int, Int>>,
        val progressTimes: Map<String, Long> = emptyMap(),
        val bookmarks: Map<String, List<Int>>,
        val favorites: List<String>,
        val categories: List<String>,
        val comicCategories: Map<String, Set<String>>,
        val readingLists: List<ReadingList>,
        val readingStats: ReadingStats,
        val readingHistory: List<ReadingHistoryEntry>,
        val comicExtras: Map<String, ComicExtras>,
        val readerSettings: ReaderSettings
    )
}

@kotlinx.serialization.Serializable
enum class ScrollMode {
    PAGE,
    VERTICAL,
    HORIZONTAL
}

@kotlinx.serialization.Serializable
enum class ZoomMode {
    FREE,
    FIT_WIDTH,
    FIT_HEIGHT,
    FIT_SCREEN
}

@Immutable
@kotlinx.serialization.Serializable
data class ReaderSettings(
    val dualPage: Boolean = false,
    val autoDualPageInLandscape: Boolean = false,
    val rtl: Boolean = false,
    val immersive: Boolean = true,
    val backgroundColor: String = "#FF000000",
    val autoCrop: Boolean = false,
    val cropMarginTop: Int = 0,
    val cropMarginBottom: Int = 0,
    val cropMarginLeft: Int = 0,
    val cropMarginRight: Int = 0,
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
    val randomAnimation: Boolean = false,
    val pageAnimSpeed: Float = 1.0f,
    val enableZoom: Boolean = false,
    val sharpenStrength: Float = 1f,
    val denoiseStrength: Float = 1f,
    val tapZoneSize: TapZoneSize = TapZoneSize.MEDIUM,
    val scaleFilter: ScaleFilter = ScaleFilter.BILINEAR,
    val magnifierEnabled: Boolean = false,
    val backgroundTexture: BackgroundTexture = BackgroundTexture.NONE,
    val appTheme: AppTheme = AppTheme.INK,
    val customSeedColor: String = "#FF5C7A94",
    val flipSoundEnabled: Boolean = false,
    val flipVibrationEnabled: Boolean = true,
    val orientationLock: OrientationLock = OrientationLock.FOLLOW_SYSTEM,
    val gestureConfig: GestureConfig = GestureConfig.PRESET_MANGA,
    val autoBrightness: Boolean = false,
    val ttsEnabled: Boolean = false,
    val ttsSpeed: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val themeAutoMode: ThemeAutoMode = ThemeAutoMode.OFF,
    val antiMistouch: Boolean = false,
    val readerStatusBar: Boolean = false,
    val thumbnailStripEnabled: Boolean = false,
    val keepScreenOn: Boolean = true
)

@kotlinx.serialization.Serializable
enum class PageAnimation {
    NONE,
    SLIDE,
    FADE,
    PIXEL,
    BLINDS,
    SHATTER,
    DOTS
}

@kotlinx.serialization.Serializable
enum class BackgroundTexture {
    NONE,
    KRAFT,
    GLASS,
    STARS,
    WOOD
}

@kotlinx.serialization.Serializable
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
    WINE("酒红幕", true, 0xFF880E4F, "#FF1A0A10", BackgroundTexture.NONE),
    CUSTOM("自定义", true, 0xFF5C7A94, "#FF121212", BackgroundTexture.NONE)
}

fun AppTheme.displayName(context: Context): String = when (this) {
    AppTheme.INK -> context.getString(R.string.theme_ink)
    AppTheme.KRAFT -> context.getString(R.string.theme_kraft)
    AppTheme.CYBER -> context.getString(R.string.theme_cyber)
    AppTheme.EYE_CARE -> context.getString(R.string.theme_eye_care)
    AppTheme.WINE -> context.getString(R.string.theme_wine)
    AppTheme.CUSTOM -> context.getString(R.string.theme_custom)
}

@kotlinx.serialization.Serializable
enum class TapZoneSize {
    SMALL,
    MEDIUM,
    LARGE
}

@kotlinx.serialization.Serializable
enum class ScaleFilter {
    BILINEAR,
    BICUBIC,
    LANCZOS3
}

@kotlinx.serialization.Serializable
enum class OrientationLock {
    FOLLOW_SYSTEM,
    PORTRAIT,
    LANDSCAPE,
    AUTO_ROTATE
}

@kotlinx.serialization.Serializable
enum class ThemeAutoMode {
    OFF,
    FOLLOW_SYSTEM,
    SCHEDULE
}

@Immutable
@kotlinx.serialization.Serializable
data class ReadingGoals(
    val dailyPages: Int = 0,
    val dailyMinutes: Int = 0
)

@Immutable
@kotlinx.serialization.Serializable
data class GoalHistoryEntry(
    val date: String,
    val pagesAchieved: Boolean = false,
    val minutesAchieved: Boolean = false
)

@Immutable
@kotlinx.serialization.Serializable
data class BookshelfScanState(
    val lastScanAt: Long = 0L,
    val knownFiles: Set<String> = emptySet()
)
