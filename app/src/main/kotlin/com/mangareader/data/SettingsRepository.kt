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
import com.mangareader.data.GestureConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json



class SettingsRepository(private val context: Context) {

    companion object {
        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }

        private val KEY_PROGRESS_PREFIX = "progress_"
        private val KEY_PROGRESS_TIME_PREFIX = "progress_time_"
        private val KEY_TOTAL_PREFIX = "total_"
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
        private val KEY_BOOKSHELF_ROOT = stringPreferencesKey("bookshelf_root")
        private val KEY_BOOKSHELF_URIS = stringPreferencesKey("bookshelf_uris")
        private val KEY_CURRENT_BOOKSHELF_INDEX = intPreferencesKey("current_bookshelf_index")
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
        private val KEY_BOOKSHELF_VIEW_MODE = stringPreferencesKey("bookshelf_view_mode")
        private val KEY_BOOKSHELF_FILTER = stringPreferencesKey("bookshelf_filter")
        private val KEY_BOOKSHELF_SORT = stringPreferencesKey("bookshelf_sort")
        private val KEY_BOOKSHELF_SELECTED_CATEGORY = stringPreferencesKey("bookshelf_selected_category")
        private val KEY_BOOKSHELF_SEARCH_QUERY = stringPreferencesKey("bookshelf_search_query")
        private val KEY_BOOKSHELF_SEARCHING = booleanPreferencesKey("bookshelf_searching")
        private val KEY_BOOKSHELF_SEARCH_HISTORY = stringPreferencesKey("bookshelf_search_history")
        private const val MAX_SEARCH_HISTORY_SIZE = 20

        // 漫画评分、短评、标签
        private val KEY_COMIC_EXTRAS_PREFIX = "extras_"
        // 用户书单
        private val KEY_READING_LISTS = stringPreferencesKey("reading_lists")
        // 阅读历史
        private val KEY_READING_HISTORY = stringPreferencesKey("reading_history")
        private const val MAX_HISTORY_SIZE = 200

        private const val KEY_BOOKSHELF_SCAN_PREFIX = "bookshelf_scan_"

        // 本地备份版本：导出时写入，导入时校验兼容性
        private const val BACKUP_VERSION = 2
        private const val MIN_SUPPORTED_BACKUP_VERSION = 1

        // 阅读设置迁移版本：递增此值 + 在 readerSettings flow 中添加迁移逻辑
        private const val READER_SETTINGS_VERSION = 2
        private val KEY_READER_SETTINGS_VERSION = intPreferencesKey("reader_settings_version")

        private val json = Json { ignoreUnknownKeys = true }
    }

    // 阅读设置异步保存的 Job，按实例隔离，避免多 Repository/多设置项互相取消。
    @Volatile
    private var saveJob: Job? = null
    // 保护 saveJob 的取消、赋值与 flush 写入，防止 saveAsync 与 flushSave 交错导致过期快照覆盖。
    private val saveJobLock = Mutex()

    // Repository 级后台作用域：saveAsync 等持久化操作不依赖 UI 生命周期，
    // 防止用户在 SettingsScreen 拖动滑块时直接返回导致 rememberCoroutineScope 被取消而丢失写入。
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val statsRepository = ReadingStatsRepository.getInstance(context)

    /**
     * 阅读设置异步流，首次读取时自动执行版本迁移。
     *
     * 迁移逻辑：确保 rtl 与手势预设始终一致。
     * 以 rtl 为权威来源，手势配置从 rtl 推导：
     * rtl=true → 日漫手势，rtl=false → 国漫手势（简洁模式保留）。
     * 修复旧版中"正常模式向右翻页页数减少"的问题（rtl=false 但手势仍是日漫的 tapRight=PREV_PAGE）。
     */
    val readerSettings: Flow<ReaderSettings> = context.userDataStore.data
        .transform { prefs ->
            val settings = parseReaderSettings(prefs)
            emit(settings)
            // 一次性迁移到最新版本
            if ((prefs[KEY_READER_SETTINGS_VERSION] ?: 0) < READER_SETTINGS_VERSION) {
                // 以 rtl 为权威：rtl 决定手势方向，而非反过来
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

    /**
     * 迁移旧版手势配置。
     *
     * 旧版 PRESET_MANGA 的 swipeLeft/swipeRight 与 PRESET_COMIC 完全相同
     * （swipeLeft=NEXT, swipeRight=PREV），导致切换预设只改点击方向不改滑动方向。
     * 检测旧版特征并替换为修复后的新版。
     */
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
                // 若旧版本保存了已移除的 CURL，则回退到无动画
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

    /**
     * 将阅读相关设置恢复为默认，但保留外观主题、自定义主题色与自动主题模式，
     * 避免用户因误操作丢失全局配色。
     */
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

    /**
     * 将图像相关滤镜与处理参数恢复为默认，保留缩放模式、裁边开关以外的图像效果。
     */
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

    /**
     * 根据当前阅读方向恢复为默认手势预设：
     * RTL（日漫）使用 PRESET_MANGA，非 RTL 使用 PRESET_COMIC。
     */
    suspend fun resetGestureSettings() {
        context.userDataStore.edit { prefs ->
            val current = parseReaderSettings(prefs)
            val preset = if (current.rtl) GestureConfig.PRESET_MANGA else GestureConfig.PRESET_COMIC
            val reset = current.copy(gestureConfig = preset)
            saveReaderSettingsToPreferences(prefs, reset)
        }
    }

    /**
     * 将阅读设置写入已有的 [MutablePreferences]，供 [saveReaderSettings] 与批量导入复用，
     * 避免在 dataStore.edit 块内再次调用 edit 导致嵌套事务。
     */
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

    /**
     * 异步保存完整阅读设置，合并 300ms 内的连续写入，避免滑块快速拖动时产生大量 DataStore 写入。
     *
     * 调用方必须传入完整的 [ReaderSettings]，Repository 在 dataStore.edit 原子块内写入，
     * 避免外部读取快照与 delay 期间其他写入交错导致的数据覆盖。连续调用时旧任务会被取消，
     * 最终只保留最新一次完整设置，保证所有字段一致性。
     *
     * 使用 Repository 级作用域而非 UI scope，确保用户退出设置页后延迟写入仍能完成。
     */
    fun saveAsync(settings: ReaderSettings) {
        repositoryScope.launch {
            saveJobLock.withLock {
                saveJob?.cancel()
                saveJob = repositoryScope.launch {
                    try {
                        delay(300)
                        context.userDataStore.edit { prefs ->
                            // 若延迟期间已被取消，避免写入过期快照。
                            if (!currentCoroutineContext().isActive) return@edit
                            saveReaderSettingsToPreferences(prefs, settings)
                        }
                    } catch (e: CancellationException) {
                        // 新写入取消旧任务是正常行为，无需处理
                        throw e
                    } catch (e: Exception) {
                        Log.e("SettingsRepository", "保存阅读设置失败", e)
                    }
                }
            }
        }
    }

    /**
     * 立即持久化指定的阅读设置，并等待任何待执行的异步保存完成。
     * 适用于需要确保设置立即落盘的场景（如退出设置页前的兜底保存）。
     */
    suspend fun flushSave(settings: ReaderSettings) {
        // 使用 Mutex 保证 cancel-join-write 全程互斥，避免 saveAsync 在 join 与 edit 之间
        // 插入新的延迟写入任务，导致 flushSave 写入后被过期快照覆盖。
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

    fun getProgress(uri: Uri): Flow<Int> = getProgress(uri, null)

    fun getProgress(uri: Uri, chapterTitle: String?): Flow<Int> = context.userDataStore.data.map { prefs ->
        prefs[intPreferencesKey(progressKey(uri, chapterTitle))]
            ?: prefs[intPreferencesKey(legacyProgressKey(uri, chapterTitle))]
            ?: 0
    }.flowOn(Dispatchers.IO)

    suspend fun saveProgress(uri: Uri, page: Int) = saveProgress(uri, null, page)

    suspend fun saveProgress(uri: Uri, chapterTitle: String?, page: Int) {
        context.userDataStore.edit { prefs ->
            val key = intPreferencesKey(progressKey(uri, chapterTitle))
            prefs[key] = page
            // 写入新键后尝试清理旧键，逐步迁移到安全分隔符。
            val legacyKey = intPreferencesKey(legacyProgressKey(uri, chapterTitle))
            if (prefs.contains(legacyKey)) {
                prefs.remove(legacyKey)
            }
            val timeKey = longPreferencesKey(KEY_PROGRESS_TIME_PREFIX + uri.toString())
            prefs[timeKey] = System.currentTimeMillis()
            // 跟踪已记录进度的 URI，用于 getAllProgress 查询。
            // 仅在 URI 未在列表中时才更新，避免同一漫画翻页时频繁序列化写入。
            val currentUri = uri.toString()
            val tracked = decodeTrackedUris(prefs[KEY_TRACKED_URIS])
            if (!tracked.contains(currentUri)) {
                val updated = (listOf(currentUri) + tracked).take(MAX_TRACKED_URIS)
                prefs[KEY_TRACKED_URIS] = json.encodeToString(ListSerializer(String.serializer()), updated)
            }
        }
    }

    /**
     * 生成进度存储键。
     * 使用 `#` 作为章节分隔符；旧版使用 `\u0000` 会导致部分存储后端异常，
     * 读写时同时兼容旧键，避免用户分卷进度丢失。
     */
    private fun progressKey(uri: Uri, chapterTitle: String?): String {
        return KEY_PROGRESS_PREFIX + uri.toString() + if (!chapterTitle.isNullOrBlank()) "#$chapterTitle" else ""
    }

    private fun legacyProgressKey(uri: Uri, chapterTitle: String?): String {
        return KEY_PROGRESS_PREFIX + uri.toString() + if (!chapterTitle.isNullOrBlank()) "\u0000$chapterTitle" else ""
    }

    fun getTotalPages(uri: Uri): Flow<Int> = context.userDataStore.data.map { prefs ->
        prefs[intPreferencesKey(KEY_TOTAL_PREFIX + uri.toString())] ?: 0
    }.flowOn(Dispatchers.IO)

    suspend fun saveTotalPages(uri: Uri, total: Int) {
        context.userDataStore.edit { prefs ->
            prefs[intPreferencesKey(KEY_TOTAL_PREFIX + uri.toString())] = total
        }
    }

    /**
     * 阅读器翻页时批量写入进度：章节进度、漫画整体进度、总页数、阅读时间、追踪 URI
     * 合并为一次 DataStore 事务，避免快速翻页时多次 edit 带来的 IO 与锁竞争。
     */
    suspend fun saveReadingProgress(
        uri: Uri,
        chapterTitle: String?,
        chapterPage: Int,
        overallPage: Int,
        totalPages: Int
    ) {
        context.userDataStore.edit { prefs ->
            val chapterKey = intPreferencesKey(progressKey(uri, chapterTitle))
            prefs[chapterKey] = chapterPage
            val legacyKey = intPreferencesKey(legacyProgressKey(uri, chapterTitle))
            if (prefs.contains(legacyKey)) {
                prefs.remove(legacyKey)
            }
            val timeKey = longPreferencesKey(KEY_PROGRESS_TIME_PREFIX + uri.toString())
            prefs[timeKey] = System.currentTimeMillis()
            val currentUri = uri.toString()
            val tracked = decodeTrackedUris(prefs[KEY_TRACKED_URIS])
            if (!tracked.contains(currentUri)) {
                val updated = (listOf(currentUri) + tracked).take(MAX_TRACKED_URIS)
                prefs[KEY_TRACKED_URIS] = json.encodeToString(ListSerializer(String.serializer()), updated)
            }
            prefs[intPreferencesKey(KEY_PROGRESS_PREFIX + currentUri)] = overallPage
            prefs[intPreferencesKey(KEY_TOTAL_PREFIX + currentUri)] = totalPages
        }
    }

    /**
     * 批量保存阅读进度与总页数：每个 URI 的整体进度、总页数、更新时间、追踪列表
     * 合并为一次 DataStore 事务，用于 WebDAV 等同步场景，避免 N 次 edit 带来的 IO 开销。
     *
     * @param progressMap uri -> overallPage
     * @param totalMap uri -> totalPages
     */
    suspend fun saveProgressBatch(
        progressMap: Map<String, Int>,
        totalMap: Map<String, Int>
    ) {
        if (progressMap.isEmpty() && totalMap.isEmpty()) return
        context.userDataStore.edit { prefs ->
            val tracked = decodeTrackedUris(prefs[KEY_TRACKED_URIS]).toMutableSet()
            var changed = false
            val now = System.currentTimeMillis()
            for ((uri, page) in progressMap) {
                prefs[intPreferencesKey(KEY_PROGRESS_PREFIX + uri)] = page
                prefs[longPreferencesKey(KEY_PROGRESS_TIME_PREFIX + uri)] = now
                if (tracked.add(uri)) changed = true
            }
            for ((uri, total) in totalMap) {
                prefs[intPreferencesKey(KEY_TOTAL_PREFIX + uri)] = total
                if (tracked.add(uri)) changed = true
            }
            if (changed) {
                prefs[KEY_TRACKED_URIS] = json.encodeToString(
                    ListSerializer(String.serializer()),
                    (tracked.toList().take(MAX_TRACKED_URIS))
                )
            }
        }
    }

    /**
     * 批量保存阅读时间戳，用于 WebDAV 等同步场景。
     * 将远程合并后的时间戳写入 DataStore，保证后续冲突解决依据一致。
     */
    suspend fun saveProgressTimesBatch(times: Map<String, Long>) {
        if (times.isEmpty()) return
        context.userDataStore.edit { prefs ->
            val tracked = decodeTrackedUris(prefs[KEY_TRACKED_URIS]).toMutableSet()
            var changed = false
            for ((uri, time) in times) {
                prefs[longPreferencesKey(KEY_PROGRESS_TIME_PREFIX + uri)] = time
                if (tracked.add(uri)) changed = true
            }
            if (changed) {
                prefs[KEY_TRACKED_URIS] = json.encodeToString(
                    ListSerializer(String.serializer()),
                    tracked.toList().take(MAX_TRACKED_URIS)
                )
            }
        }
    }

    /**
     * 返回所有已记录阅读进度的漫画：uri -> (progress, total)。
     * 用于书架计算阅读状态（未读/阅读中/已读）。
     */
    fun getAllProgress(): Flow<Map<String, Pair<Int, Int>>> = context.userDataStore.data.map { prefs ->
        val tracked = decodeTrackedUris(prefs[KEY_TRACKED_URIS])
        if (tracked.isEmpty()) return@map emptyMap()
        // 一次性获取所有键值，避免多次索引查找
        val all = prefs.asMap()
        val result = HashMap<String, Pair<Int, Int>>(tracked.size)
        for (uriStr in tracked) {
            val baseProgress = all[intPreferencesKey(KEY_PROGRESS_PREFIX + uriStr)] as? Int
            val total = (all[intPreferencesKey(KEY_TOTAL_PREFIX + uriStr)] as? Int) ?: 0
            val progress = baseProgress ?: run {
                // 若整体进度不存在，查找该漫画下的分卷进度，取最大值作为整体进度。
                // 同时兼容旧版 `\u0000` 分隔符与新 `#` 分隔符。
                val prefix = KEY_PROGRESS_PREFIX + uriStr
                all.entries.filter {
                    val name = it.key.name
                    name.startsWith(prefix) && name.length > prefix.length &&
                        name[prefix.length] in setOf('\u0000', '#')
                }.mapNotNull { it.value as? Int }.maxOrNull() ?: 0
            }
            result[uriStr] = Pair(progress, total)
        }
        result
    }.flowOn(Dispatchers.IO)

    /**
     * 返回所有已记录阅读进度的漫画及其最后阅读时间：uri -> timestamp。
     * 用于书架「最近阅读」排序。
     */
    fun getRecentReads(): Flow<Map<String, Long>> = context.userDataStore.data.map { prefs ->
        val tracked = decodeTrackedUris(prefs[KEY_TRACKED_URIS])
        if (tracked.isEmpty()) return@map emptyMap()
        val all = prefs.asMap()
        val result = HashMap<String, Long>(tracked.size)
        for (uriStr in tracked) {
            val time = all[longPreferencesKey(KEY_PROGRESS_TIME_PREFIX + uriStr)] as? Long ?: 0L
            if (time > 0L) {
                result[uriStr] = time
            }
        }
        result
    }.flowOn(Dispatchers.IO)

    /** 标记为已读：进度设为最后一页 */
    suspend fun markAsRead(uri: Uri, totalPages: Int) {
        saveProgress(uri, (totalPages - 1).coerceAtLeast(0))
    }

    /** 标记为未读：进度归零 */
    suspend fun markAsUnread(uri: Uri) {
        saveProgress(uri, 0)
    }

    fun getBookshelfRoot(): Flow<String?> = context.userDataStore.data.map { prefs ->
        prefs[KEY_BOOKSHELF_ROOT]
    }.flowOn(Dispatchers.IO)

    suspend fun saveBookshelfRoot(uriString: String) {
        context.userDataStore.edit { prefs ->
            prefs[KEY_BOOKSHELF_ROOT] = uriString
        }
    }

    // ---------- 多书架支持 ----------

    fun getBookshelfUris(): Flow<List<String>> = context.userDataStore.data.map { prefs ->
        parseBookshelfUris(prefs[KEY_BOOKSHELF_URIS])
    }.flowOn(Dispatchers.IO)

    suspend fun saveBookshelfUris(uris: List<String>) {
        context.userDataStore.edit { prefs ->
            prefs[KEY_BOOKSHELF_URIS] = uris.joinToString("|")
        }
    }

    suspend fun addBookshelfUri(uriString: String) {
        context.userDataStore.edit { prefs ->
            val list = parseBookshelfUris(prefs[KEY_BOOKSHELF_URIS]).toMutableList()
            if (!list.contains(uriString)) list.add(uriString)
            prefs[KEY_BOOKSHELF_URIS] = list.joinToString("|")
        }
    }

    suspend fun removeBookshelfUri(uriString: String) {
        context.userDataStore.edit { prefs ->
            val list = parseBookshelfUris(prefs[KEY_BOOKSHELF_URIS]).filter { it != uriString }
            prefs[KEY_BOOKSHELF_URIS] = list.joinToString("|")
        }
    }

    fun getCurrentBookshelfIndex(): Flow<Int> = context.userDataStore.data.map { prefs ->
        prefs[KEY_CURRENT_BOOKSHELF_INDEX] ?: 0
    }.flowOn(Dispatchers.IO)

    suspend fun saveCurrentBookshelfIndex(index: Int) {
        context.userDataStore.edit { prefs ->
            prefs[KEY_CURRENT_BOOKSHELF_INDEX] = index
        }
    }

    private fun parseBookshelfUris(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split("|").filter { it.isNotEmpty() }
    }

    fun getBookmarks(): Flow<Map<String, List<Int>>> = context.userDataStore.data.map { prefs ->
        decodeBookmarks(prefs[KEY_BOOKMARKS])
    }.flowOn(Dispatchers.IO)

    suspend fun addBookmark(uri: Uri, page: Int) {
        context.userDataStore.edit { prefs ->
            val map = decodeBookmarks(prefs[KEY_BOOKMARKS]).toMutableMap()

            val key = uri.toString()
            val list = (map[key] ?: emptyList()).toMutableSet().apply { add(page) }.toSortedSet()
            map[key] = list.toList()

            prefs[KEY_BOOKMARKS] = encodeBookmarks(map)
        }
    }

    suspend fun removeBookmark(uri: Uri, page: Int) {
        context.userDataStore.edit { prefs ->
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

    /**
     * 直接覆盖指定漫画的书签列表。用于退出阅读器时把内存中的书签状态完整同步到磁盘，
     * 避免最后一刻的增删协程被取消而丢失。
     */
    suspend fun saveBookmarks(uri: Uri, pages: List<Int>) {
        context.userDataStore.edit { prefs ->
            val map = decodeBookmarks(prefs[KEY_BOOKMARKS]).toMutableMap()
            val key = uri.toString()
            if (pages.isEmpty()) {
                map.remove(key)
            } else {
                map[key] = pages.distinct().sorted()
            }
            prefs[KEY_BOOKMARKS] = encodeBookmarks(map)
        }
    }

    /**
     * 批量合并书签：将 [uriToPages] 中的页码合并到现有书签中。
     * 用于 WebDAV 等同步场景，避免单条写入触发大量 DataStore 事务。
     */
    suspend fun mergeBookmarks(uriToPages: Map<String, List<Int>>) {
        if (uriToPages.isEmpty()) return
        context.userDataStore.edit { prefs ->
            val map = decodeBookmarks(prefs[KEY_BOOKMARKS]).toMutableMap()
            for ((uri, pages) in uriToPages) {
                val existing = map[uri] ?: emptyList()
                map[uri] = (existing + pages).distinct().sorted()
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

    fun getFavorites(): Flow<Set<String>> = context.userDataStore.data.map { prefs ->
        parseStringSet(prefs[KEY_FAVORITES])
    }.flowOn(Dispatchers.IO)

    suspend fun toggleFavorite(uri: Uri) {
        context.userDataStore.edit { prefs ->
            val set = parseStringSet(prefs[KEY_FAVORITES]).toMutableSet()
            val key = uri.toString()
            if (set.contains(key)) set.remove(key) else set.add(key)
            prefs[KEY_FAVORITES] = serializeStringSet(set)
        }
    }

    /**
     * 批量添加收藏，仅把 [uris] 中尚未收藏的 URI 加入收藏集。
     * 用于 WebDAV 等同步场景，避免单条写入触发大量 DataStore 事务。
     */
    suspend fun addFavorites(uris: Set<String>) {
        if (uris.isEmpty()) return
        context.userDataStore.edit { prefs ->
            val set = parseStringSet(prefs[KEY_FAVORITES]).toMutableSet()
            var changed = false
            for (uri in uris) {
                if (set.add(uri)) changed = true
            }
            if (changed) {
                prefs[KEY_FAVORITES] = serializeStringSet(set)
            }
        }
    }

    // ---------- 分类管理 ----------

    /**
     * 获取所有分类名称列表。
     * 兼容旧版 "分类1|分类2" 格式与新版 JSON 格式。
     */
    fun getCategories(): Flow<List<String>> = context.userDataStore.data.map { prefs ->
        parseStringList(prefs[KEY_CATEGORIES])
    }.flowOn(Dispatchers.IO)

    suspend fun addCategory(name: String) {
        context.userDataStore.edit { prefs ->
            val list = parseStringList(prefs[KEY_CATEGORIES]).toMutableList()
            if (!list.contains(name)) list.add(name)
            prefs[KEY_CATEGORIES] = serializeStringList(list)
        }
    }

    suspend fun removeCategory(name: String) {
        context.userDataStore.edit { prefs ->
            val list = parseStringList(prefs[KEY_CATEGORIES]).filter { it != name }
            prefs[KEY_CATEGORIES] = serializeStringList(list)
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
    fun getComicCategories(): Flow<Map<String, Set<String>>> = context.userDataStore.data.map { prefs ->
        val raw = prefs[KEY_COMIC_CATEGORIES] ?: return@map emptyMap()
        parseComicCategoryMap(raw)
    }.flowOn(Dispatchers.IO)

    /**
     * 将漫画加入/移出某分类
     */
    suspend fun toggleComicCategory(uri: Uri, category: String) {
        context.userDataStore.edit { prefs ->
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

    // ---------- 漫画评分、短评、标签 ----------

    fun getComicExtras(uri: Uri): Flow<ComicExtras> = context.userDataStore.data.map { prefs ->
        parseComicExtras(prefs[stringPreferencesKey(KEY_COMIC_EXTRAS_PREFIX + uri.toString())])
    }.flowOn(Dispatchers.IO)

    fun getAllComicExtras(): Flow<Map<String, ComicExtras>> = context.userDataStore.data.map { prefs ->
        prefs.asMap().mapNotNull { (key, value) ->
            val keyName = key.name
            if (keyName.startsWith(KEY_COMIC_EXTRAS_PREFIX) && value is String) {
                val uriString = keyName.removePrefix(KEY_COMIC_EXTRAS_PREFIX)
                uriString to parseComicExtras(value)
            } else null
        }.toMap()
    }.flowOn(Dispatchers.IO)

    suspend fun saveComicExtras(uri: Uri, extras: ComicExtras) {
        context.userDataStore.edit { prefs ->
            prefs[stringPreferencesKey(KEY_COMIC_EXTRAS_PREFIX + uri.toString())] = json.encodeToString(extras)
        }
    }

    private fun parseComicExtras(raw: String?): ComicExtras {
        if (raw.isNullOrBlank()) return ComicExtras()
        return runCatching { json.decodeFromString<ComicExtras>(raw) }.getOrDefault(ComicExtras())
    }

    // ---------- 书单 ----------

    fun getReadingLists(): Flow<List<ReadingList>> = context.userDataStore.data.map { prefs ->
        parseReadingLists(prefs[KEY_READING_LISTS])
    }.flowOn(Dispatchers.IO)

    suspend fun saveReadingLists(lists: List<ReadingList>) {
        context.userDataStore.edit { prefs ->
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

    // ---------- 阅读统计（委托给 ReadingStatsRepository） ----------

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

    // ---------- 阅读历史 ----------

    /**
     * 返回阅读历史列表，按最后访问时间倒序排列。
     */
    fun getReadingHistory(): Flow<List<ReadingHistoryEntry>> = context.userDataStore.data.map { prefs ->
        parseReadingHistory(prefs[KEY_READING_HISTORY])
    }.flowOn(Dispatchers.IO)

    /**
     * 记录一条阅读历史（打开漫画时调用）。
     * 若 uri 已存在，更新时间和页码；否则插入新的记录到队首。
     */
    suspend fun addReadingHistory(entry: ReadingHistoryEntry) {
        context.userDataStore.edit { prefs ->
            val list = parseReadingHistory(prefs[KEY_READING_HISTORY]).toMutableList()
            list.removeAll { it.uriString == entry.uriString }
            list.add(0, entry)
            // 超出容量时裁剪末尾（最旧的记录）
            while (list.size > MAX_HISTORY_SIZE) list.removeAt(list.size - 1)
            prefs[KEY_READING_HISTORY] = json.encodeToString(list)
        }
    }

    /**
     * 删除指定 uri 的阅读历史；若 uriString 为 null 则清空全部。
     */
    suspend fun clearReadingHistory(uriString: String? = null) {
        context.userDataStore.edit { prefs ->
            if (uriString == null) {
                prefs.remove(KEY_READING_HISTORY)
            } else {
                val list = parseReadingHistory(prefs[KEY_READING_HISTORY]).toMutableList()
                list.removeAll { it.uriString == uriString }
                prefs[KEY_READING_HISTORY] = json.encodeToString(list)
            }
        }
    }

    private fun parseReadingHistory(raw: String?): List<ReadingHistoryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<ReadingHistoryEntry>>(raw) }.getOrDefault(emptyList())
    }

    // ---------- 书架视图模式 ----------

    /**
     * 返回书架视图模式，默认网格视图。
     */
    fun getBookshelfViewMode(): Flow<String> = context.userDataStore.data.map { prefs ->
        prefs[KEY_BOOKSHELF_VIEW_MODE] ?: "GRID"
    }.flowOn(Dispatchers.IO)

    /**
     * 保存书架视图模式。
     */
    suspend fun saveBookshelfViewMode(mode: String) {
        context.userDataStore.edit { prefs ->
            prefs[KEY_BOOKSHELF_VIEW_MODE] = mode
        }
    }

    // ---------- 书架筛选 / 排序 / 当前分类 ----------

    /**
     * 返回书架筛选条件，默认全部。
     */
    fun getBookshelfFilter(): Flow<String> = context.userDataStore.data.map { prefs ->
        prefs[KEY_BOOKSHELF_FILTER] ?: "ALL"
    }.flowOn(Dispatchers.IO)

    suspend fun saveBookshelfFilter(filter: String) {
        context.userDataStore.edit { prefs ->
            prefs[KEY_BOOKSHELF_FILTER] = filter
        }
    }

    /**
     * 返回书架排序方式，默认按名称。
     */
    fun getBookshelfSort(): Flow<String> = context.userDataStore.data.map { prefs ->
        prefs[KEY_BOOKSHELF_SORT] ?: "NAME"
    }.flowOn(Dispatchers.IO)

    suspend fun saveBookshelfSort(sort: String) {
        context.userDataStore.edit { prefs ->
            prefs[KEY_BOOKSHELF_SORT] = sort
        }
    }

    /**
     * 返回书架当前选中的分类，null 表示未选择分类。
     */
    fun getBookshelfSelectedCategory(): Flow<String?> = context.userDataStore.data.map { prefs ->
        prefs[KEY_BOOKSHELF_SELECTED_CATEGORY]
    }.flowOn(Dispatchers.IO)

    suspend fun saveBookshelfSelectedCategory(category: String?) {
        context.userDataStore.edit { prefs ->
            if (category != null) {
                prefs[KEY_BOOKSHELF_SELECTED_CATEGORY] = category
            } else {
                prefs.remove(KEY_BOOKSHELF_SELECTED_CATEGORY)
            }
        }
    }

    /**
     * 返回书架搜索关键词。
     */
    fun getBookshelfSearchQuery(): Flow<String> = context.userDataStore.data.map { prefs ->
        prefs[KEY_BOOKSHELF_SEARCH_QUERY] ?: ""
    }.flowOn(Dispatchers.IO)

    suspend fun saveBookshelfSearchQuery(query: String) {
        context.userDataStore.edit { prefs ->
            if (query.isBlank()) {
                prefs.remove(KEY_BOOKSHELF_SEARCH_QUERY)
            } else {
                prefs[KEY_BOOKSHELF_SEARCH_QUERY] = query
            }
        }
    }

    /**
     * 返回书架搜索框是否展开。
     */
    fun isBookshelfSearching(): Flow<Boolean> = context.userDataStore.data.map { prefs ->
        prefs[KEY_BOOKSHELF_SEARCHING] ?: false
    }.flowOn(Dispatchers.IO)

    suspend fun saveBookshelfSearching(searching: Boolean) {
        context.userDataStore.edit { prefs ->
            prefs[KEY_BOOKSHELF_SEARCHING] = searching
        }
    }

    /**
     * 返回书架搜索历史（最近使用的搜索关键词列表，去重，新记录在前）。
     */
    fun getBookshelfSearchHistory(): Flow<List<String>> = context.userDataStore.data.map { prefs ->
        parseStringList(prefs[KEY_BOOKSHELF_SEARCH_HISTORY] ?: "")
    }.flowOn(Dispatchers.IO)

    /**
     * 将 [query] 加入书架搜索历史。
     * - 空/纯空白查询不会被记录。
     * - 与已有记录去重，新记录排在最前。
     * - 超过 [MAX_SEARCH_HISTORY_SIZE] 时移除最旧的记录。
     */
    suspend fun addBookshelfSearchHistory(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        context.userDataStore.edit { prefs ->
            val current = parseStringList(prefs[KEY_BOOKSHELF_SEARCH_HISTORY] ?: "")
            val updated = buildList {
                add(trimmed)
                current.filter { it != trimmed }.take(MAX_SEARCH_HISTORY_SIZE - 1).forEach { add(it) }
            }
            prefs[KEY_BOOKSHELF_SEARCH_HISTORY] = json.encodeToString(updated)
        }
    }

    suspend fun clearBookshelfSearchHistory() {
        context.userDataStore.edit { prefs ->
            prefs.remove(KEY_BOOKSHELF_SEARCH_HISTORY)
        }
    }

    // ---------- 书架扫描状态（新文件检测） ----------

    fun getBookshelfScanState(rootUriString: String): Flow<BookshelfScanState> = context.userDataStore.data.map { prefs ->
        val key = stringPreferencesKey(KEY_BOOKSHELF_SCAN_PREFIX + rootUriString)
        parseBookshelfScanState(prefs[key])
    }.flowOn(Dispatchers.IO)

    suspend fun saveBookshelfScanState(rootUriString: String, state: BookshelfScanState) {
        context.userDataStore.edit { prefs ->
            val key = stringPreferencesKey(KEY_BOOKSHELF_SCAN_PREFIX + rootUriString)
            prefs[key] = json.encodeToString(state)
        }
    }

    /**
     * 将指定书架的当前文件列表标记为已读，清除 NEW 角标。
     */
    suspend fun markBookshelfFilesSeen(rootUriString: String, currentFiles: Set<String>) {
        context.userDataStore.edit { prefs ->
            val key = stringPreferencesKey(KEY_BOOKSHELF_SCAN_PREFIX + rootUriString)
            prefs[key] = json.encodeToString(
                BookshelfScanState(
                    lastScanAt = System.currentTimeMillis(),
                    knownFiles = currentFiles
                )
            )
        }
    }

    private fun parseBookshelfScanState(raw: String?): BookshelfScanState {
        if (raw.isNullOrBlank()) return BookshelfScanState()
        return runCatching { json.decodeFromString<BookshelfScanState>(raw) }.getOrDefault(BookshelfScanState())
    }

    // ---------- 本地备份与恢复 ----------

    /**
     * 导出所有用户数据为 JSON 字符串。
     * 包含：阅读进度、阅读时间戳、书签、收藏、分类、漫画分类、书单、阅读统计、历史、评分标签、阅读设置。
     */
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

    /**
     * 从 JSON 字符串导入用户数据。
     * @param overwrite 为 true 时覆盖本地数据；为 false 时与本地合并（进度取最大值，集合取并集）。
     */
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
            // 版本迁移钩子：未来若 BackupData 字段变更，可在此按版本逐步转换
            val migratedBackup = migrateBackupIfNeeded(backup)
            val existingExtras = if (overwrite) emptyMap() else getAllComicExtras().first()
            context.userDataStore.edit { prefs ->
                // 阅读进度：取最大值
                val existingProgress = if (overwrite) emptyMap() else parseAllProgress(prefs)
                val mergedProgress = (existingProgress.keys + migratedBackup.progress.keys).associateWith { key ->
                    val existing = existingProgress[key] ?: (0 to 0)
                    val remote = migratedBackup.progress[key] ?: (0 to 0)
                    maxOf(existing.first, remote.first) to maxOf(existing.second, remote.second)
                }
                saveAllProgressInternal(prefs, mergedProgress)

                // 阅读时间戳：合并，保留较新的时间
                val existingProgressTimes = if (overwrite) emptyMap() else parseProgressTimes(prefs)
                val mergedProgressTimes = (existingProgressTimes.keys + migratedBackup.progressTimes.keys).associateWith { key ->
                    maxOf(existingProgressTimes[key] ?: 0L, migratedBackup.progressTimes[key] ?: 0L)
                }
                saveProgressTimesInternal(prefs, mergedProgressTimes)

                // 书签：合并并去重排序
                val existingBookmarks = if (overwrite) emptyMap() else parseBookmarksMap(prefs[KEY_BOOKMARKS])
                val mergedBookmarks = (existingBookmarks.keys + migratedBackup.bookmarks.keys).associateWith { key ->
                    ((existingBookmarks[key] ?: emptyList()) + (migratedBackup.bookmarks[key] ?: emptyList())).distinct().sorted()
                }
                prefs[KEY_BOOKMARKS] = json.encodeToString(mergedBookmarks)

                // 收藏：并集
                val existingFavorites = if (overwrite) emptySet() else parseStringSet(prefs[KEY_FAVORITES])
                prefs[KEY_FAVORITES] = json.encodeToString((existingFavorites + migratedBackup.favorites.toSet()).toList())

                // 分类：并集保留顺序
                val existingCategories = if (overwrite) emptyList() else parseStringList(prefs[KEY_CATEGORIES])
                prefs[KEY_CATEGORIES] = json.encodeToString((existingCategories + migratedBackup.categories).distinct())

                // 漫画分类：合并
                val existingComicCategories = if (overwrite) emptyMap() else parseComicCategories(prefs[KEY_COMIC_CATEGORIES])
                val mergedComicCategories = (existingComicCategories.keys + migratedBackup.comicCategories.keys).associateWith { key ->
                    (existingComicCategories[key] ?: emptySet()) + (migratedBackup.comicCategories[key] ?: emptySet())
                }
                prefs[KEY_COMIC_CATEGORIES] = json.encodeToString(mergedComicCategories)

                // 书单：按名称合并，去重保留
                val existingLists = if (overwrite) emptyList() else parseReadingLists(prefs[KEY_READING_LISTS])
                val listMap = existingLists.associateBy { it.name }.toMutableMap()
                for (list in migratedBackup.readingLists) {
                    listMap[list.name] = list.copy(
                        items = ((listMap[list.name]?.items ?: emptyList()) + list.items).distinct()
                    )
                }
                prefs[KEY_READING_LISTS] = json.encodeToString(listMap.values.toList())

                // 阅读统计：在独立事务中合并，避免嵌套 edit（见下方 statsRepository.mergeReadingStats）

                // 阅读历史：合并，新的放前面
                val existingHistory = if (overwrite) emptyList() else parseReadingHistory(prefs[KEY_READING_HISTORY])
                val historyMap = existingHistory.associateBy { it.uriString }.toMutableMap()
                for (entry in migratedBackup.readingHistory) {
                    val old = historyMap[entry.uriString]
                    if (old == null || entry.lastReadAt > old.lastReadAt) {
                        historyMap[entry.uriString] = entry
                    }
                }
                val mergedHistory = historyMap.values.sortedByDescending { it.lastReadAt }.take(MAX_HISTORY_SIZE)
                prefs[KEY_READING_HISTORY] = json.encodeToString(mergedHistory)

                // 漫画评分标签：按 URI 覆盖或合并
                val mergedExtras = (existingExtras.keys + migratedBackup.comicExtras.keys).associateWith { key ->
                    existingExtras[key]?.mergeWith(migratedBackup.comicExtras[key]) ?: (migratedBackup.comicExtras[key] ?: ComicExtras())
                }
                for ((uri, extras) in mergedExtras) {
                    prefs[stringPreferencesKey(KEY_COMIC_EXTRAS_PREFIX + uri)] = json.encodeToString(extras)
                }

                // 阅读设置：仅在覆盖时写入；复用 helper 避免嵌套 edit
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

    /**
     * 备份版本迁移钩子。当前版本与 [BACKUP_VERSION] 一致时直接返回；
     * 未来若 BackupData 字段发生不兼容变更，可在此按版本逐步转换，避免旧备份无法恢复。
     */
    private fun migrateBackupIfNeeded(backup: BackupData): BackupData {
        var current = backup
        if (current.version < BACKUP_VERSION) {
            // 预留：v1 -> v2 的字段映射/默认值填充可在此实现
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
        val tracked = decodeTrackedUris(prefs[KEY_TRACKED_URIS])
        return tracked.associateWith { uri ->
            val page = prefs[intPreferencesKey(KEY_PROGRESS_PREFIX + uri)] ?: 0
            val total = prefs[intPreferencesKey(KEY_TOTAL_PREFIX + uri)] ?: 0
            page to total
        }
    }

    private fun parseProgressTimes(prefs: Preferences): Map<String, Long> {
        val tracked = decodeTrackedUris(prefs[KEY_TRACKED_URIS])
        return tracked.associateWith { uri ->
            prefs[longPreferencesKey(KEY_PROGRESS_TIME_PREFIX + uri)] ?: 0L
        }.filter { it.value > 0L }
    }

    private fun saveProgressTimesInternal(prefs: MutablePreferences, times: Map<String, Long>) {
        val tracked = decodeTrackedUris(prefs[KEY_TRACKED_URIS]).toMutableSet()
        for ((uri, time) in times) {
            prefs[longPreferencesKey(KEY_PROGRESS_TIME_PREFIX + uri)] = time
            tracked += uri
        }
        prefs[KEY_TRACKED_URIS] = json.encodeToString(tracked.take(MAX_TRACKED_URIS))
    }

    private fun parseBookmarksMap(raw: String?): Map<String, List<Int>> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, List<Int>>>(raw) }.getOrDefault(emptyMap())
    }

    private fun parseStringSet(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        val decoded = runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull()
        if (decoded != null) return decoded.toSet()
        // 兼容旧版 pipe 分隔
        return raw.split("|").filter { it.isNotEmpty() }.toSet()
    }

    private fun serializeStringSet(value: Set<String>): String =
        json.encodeToString(value.toList())

    private fun parseStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val decoded = runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull()
        if (decoded != null) return decoded
        // 兼容旧版 pipe 分隔
        return raw.split("|").filter { it.isNotEmpty() }
    }

    private fun serializeStringList(value: List<String>): String =
        json.encodeToString(value)

    private fun parseComicCategories(raw: String?): Map<String, Set<String>> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, Set<String>>>(raw) }.getOrDefault(emptyMap())
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
    PAGE,       // 单/双页模式
    VERTICAL,   // 垂直滚动（条漫）
    HORIZONTAL  // 水平滚动
}

@kotlinx.serialization.Serializable
enum class ZoomMode {
    FREE,        // 自由缩放（默认）
    FIT_WIDTH,   // 适合宽度
    FIT_HEIGHT,  // 适合高度
    FIT_SCREEN   // 适合屏幕
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
    val randomAnimation: Boolean = false,       // 每次翻页随机一种动效
    val pageAnimSpeed: Float = 1.0f,           // 翻页动画速度倍率 (0.2f - 3.0f)
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
    NONE,       // 无动画
    SLIDE,      // 滑动
    FADE,       // 淡入淡出
    PIXEL,      // 像素风（盲盒动效）
    BLINDS,     // 百叶窗
    SHATTER,    // 玻璃碎裂
    DOTS        // 漫画网点
}

@kotlinx.serialization.Serializable
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
    SMALL,      // 小点击区域（中间菜单区大）
    MEDIUM,     // 中等
    LARGE        // 大点击区域（翻页区大）
}

@kotlinx.serialization.Serializable
enum class ScaleFilter {
    BILINEAR,   // 双线性（默认，快）
    BICUBIC,    // 双三次（较平滑）
    LANCZOS3    // Lanczos3（最锐利，适合低清扫描放大）
}

@kotlinx.serialization.Serializable
enum class OrientationLock {
    FOLLOW_SYSTEM, // 跟随系统
    PORTRAIT,      // 竖屏锁定
    LANDSCAPE,     // 横屏锁定
    AUTO_ROTATE    // 自动旋转（允许传感器）
}

/**
 * 主题自动切换模式。
 */
@kotlinx.serialization.Serializable
enum class ThemeAutoMode {
    OFF,           // 关闭，使用用户选择的主题
    FOLLOW_SYSTEM, // 跟随系统深色模式
    SCHEDULE       // 按时间段（22:00-06:00 使用深色）
}

/**
 * 每日阅读目标。
 * dailyPages / dailyMinutes 为 0 时表示未启用对应目标。
 */
@Immutable
@kotlinx.serialization.Serializable
data class ReadingGoals(
    val dailyPages: Int = 0,
    val dailyMinutes: Int = 0
)

/**
 * 单日目标达成记录。
 */
@Immutable
@kotlinx.serialization.Serializable
data class GoalHistoryEntry(
    val date: String, // yyyy-MM-dd
    val pagesAchieved: Boolean = false,
    val minutesAchieved: Boolean = false
)

/**
 * 单书架扫描状态，用于检测新加入的漫画文件。
 */
@Immutable
@kotlinx.serialization.Serializable
data class BookshelfScanState(
    val lastScanAt: Long = 0L,
    val knownFiles: Set<String> = emptySet()
)
