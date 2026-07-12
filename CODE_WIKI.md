# MangaReader 漫画阅读器 - Code Wiki

## 1. 项目概述

MangaReader 是一款基于 Android Jetpack Compose 的本地漫画阅读器，采用「内容优先，UI 隐形」的设计理念，支持多种漫画格式，具备完整的书架管理、分卷浏览和沉浸式阅读功能。

**主要功能特性：**
- 支持 CBZ、CBR、CB7、CBT、EPUB、PDF、文件夹等多种漫画格式
- 书架功能：将文件夹作为漫画系列管理，子文件作为分卷/章节
- 文件浏览器：支持本地存储和 SAF（Storage Access Framework）浏览
- 分卷浏览：进入漫画先展示分卷缩略图（Perfect Viewer 风格）
- 多种阅读模式：单页、双页、垂直滚动、水平滚动
- 丰富的阅读设置：亮度/对比度/Gamma/饱和度、自动裁白边、灰度、锐化、降噪、镜像等
- 手势热区映射：支持日漫/美漫点击区域预设，翻页与呼出菜单分离
- 主题皮肤系统：经典黑、牛皮纸、赛博朋克、护眼绿、酒红幕
- 大范围内存缓存：阅读时自动预加载当前页前后各 15 页，退出阅读时自动清空
- LRU 磁盘缓存：缓存原始图片字节，页面 30MB + 缩略图 10MB
- 压缩包随机读取：不解压全包，按页按需解压
- 书签和收藏功能，支持缩略图预览与跳转
- 阅读进度自动保存
- 沉浸式全屏阅读，轻触中央呼出极简控制栏

---

## 2. 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | Kotlin | 1.9.x |
| UI框架 | Jetpack Compose | 2024.02.00 |
| 导航 | Navigation Compose | 2.7.7 |
| MVVM | AndroidX Lifecycle | 2.7.0 |
| 存储 | DataStore Preferences | 1.0.0 |
| 图片加载 | Coil | 2.5.0 |
| RAR支持 | junrar | 7.5.4 |
| 7z支持 | Commons Compress | 1.26.0 |
| Android SDK | Min | 26 |
| Android SDK | Target | 34 |

---

## 3. 项目架构

### 3.1 整体架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                         MainActivity                           │
│                         (入口Activity)                          │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                     MangaReaderApp                              │
│              (Compose导航宿主, 管理五个Screen)                   │
└──────────┬──────────────┬─────────────────┬──────────────────────┘
           │              │                 │
  ┌────────▼──────┐ ┌─────▼──────┐ ┌───────▼────────┐ ┌───────────▼─────────┐
  │BookshelfScreen│ │BrowserScreen│ │ChapterBrowser  │ │   ReaderScreen      │
  │     (书架)    │ │   (文件浏览) │ │   (分卷浏览)    │ │     (阅读器)        │
  └───────┬───────┘ └─────┬──────┘ └───────┬────────┘ └───────────┬─────────┘
          │               │                │                      │
  ┌───────▼───────┐ ┌─────▼──────┐ ┌──────▼─────────┐ ┌───────────▼─────────┐
  │BookshelfViewModel│ │BrowserViewModel│ │ChapterBrowser  │ │  ReaderViewModel   │
  └───────┬───────┘ └─────┬──────┘ └──────┬─────────┘ └───────────┬─────────┘
          │               │               │                       │
          └───────────────┴───────────────┴───────────────────────┘
                                  │
                                  ▼
                    ┌──────────────────────────┐
                    │    SettingsRepository    │
                    │   (DataStore持久化存储)    │
                    └──────────────────────────┘
                                  │
                    ┌──────────────┼──────────────┐
                    ▼              ▼              ▼
            ┌──────────┐   ┌────────────┐   ┌───────────┐
            │ Provider │   │   Cache    │   │   Utils   │
            │(统一抽象) │   │(内存+磁盘)  │   │ (工具类)  │
            └─────┬────┘   └─────┬──────┘   └─────┬─────┘
                  │              │                │
            ┌─────▼────┐   ┌─────▼─────┐   ┌──────▼─────┐
            │  Parser  │   │   Models  │   │   Theme    │
            │  (解析器) │   │ (数据模型)  │   │ (主题皮肤)  │
            └──────────┘   └───────────┘   └────────────┘
```

### 3.2 模块职责

| 模块 | 职责 | 文件数量 | 说明 |
|------|------|----------|------|
| `bookshelf` | 书架功能，管理漫画列表与最近阅读 | 3 | BookshelfScreen, BookshelfViewModel, BookshelfExtras |
| `browser` | 文件浏览器，浏览本地存储 | 2 | BrowserScreen, BrowserViewModel |
| `chapters` | 分卷浏览，展示分卷/章节缩略图 | 2 | ChapterBrowserScreen, ChapterBrowserViewModel |
| `reader` | 漫画阅读器，核心阅读功能 | 3 | ReaderScreen, ReaderViewModel, PageCurlEffect |
| `settings` | 独立设置页 | 1 | SettingsScreen |
| `ui/theme` | 主题皮肤系统 | 1 | MangaReaderTheme |
| `onboarding` | 引导页/使用说明 | 1 | OnboardingHost |
| `parser` | 漫画格式解析器 | 10 | 支持7种格式解析器 + Factory + PageLoader |
| `provider` | ComicProvider 统一抽象 | 7 | 本地/压缩包/网络/插件提供者 |
| `data` | 数据模型和存储管理 | 4 | Models, SettingsRepository, ComicExtras, GestureConfig |
| `cache` | 页面缓存与磁盘 LRU 缓存 | 2 | PageCacheManager, DiskLruCache |
| `utils` | 工具类 | 10+ | ImageUtils, PermissionUtils, PanelDetector, MetadataScraper 等 |

---

## 4. 模块详细说明

### 4.1 入口模块 (`com.mangareader`)

#### MainActivity [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/MainActivity.kt)

应用入口 Activity，负责初始化 Compose 内容并传递启动 URI。

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Surface(modifier = Modifier.fillMaxSize()) {
                MangaReaderApp(startUri = intent?.data)
            }
        }
    }
}
```

**关键特性：**
- 启用 Edge-to-Edge 全屏模式
- 通过 `intent?.data` 支持从文件管理器打开漫画文件

#### MangaReaderApp [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/MangaReaderApp.kt)

Compose 应用主组件，管理五个 Screen 的导航逻辑。

**核心组件：**

| 组件 | 说明 |
|------|------|
| `Screen` 密封类 | 定义五个导航页面（Bookshelf、Browser、ChapterBrowser、Reader、Settings） |
| `NavHost` | Compose Navigation 导航宿主 |
| `LaunchedEffect(startUri)` | 处理从外部打开漫画文件的逻辑 |
| `openComic` | 统一的漫画打开回调，进入分卷浏览而非直接阅读 |
| `MangaReaderTheme` | 应用全局主题皮肤 |
| `OnboardingHost` | 首次使用引导与帮助页 |

**导航路由设计：**

```kotlin
sealed class Screen(val route: String) {
    data object Bookshelf : Screen("bookshelf")
    data object Browser : Screen("browser")
    data object ChapterBrowser : Screen("chapter_browser")
    data object Reader : Screen("reader")
    data object Settings : Screen("settings")
}
```

**URI安全处理：** 漫画 entry 通过 `ComicHolder` 单例传递，避免 Navigation 对 content:// URI 的转义/反转义导致路径损坏。

---

### 4.2 书架模块 (`bookshelf`)

#### BookshelfViewModel [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/bookshelf/BookshelfViewModel.kt)

书架数据管理，负责加载和管理漫画列表。

**核心函数：**

| 函数 | 功能 |
|------|------|
| `setRoot(uri)` | 设置书架根目录并加载漫画 |
| `refresh()` | 刷新当前书架 |
| `loadBookshelf(rootUri)` | 加载书架核心逻辑 |
| `loadFileBookshelf(root)` | 加载 file:// 协议的文件系统 |
| `loadDocumentBookshelf(rootUri)` | 加载 content:// SAF 协议的文件系统 |
| `navigateUp()` | 返回上级目录（防止子目录无限嵌套） |
| `getReadingStatus(uri)` | 根据阅读进度计算未读/阅读中/已读状态 |

**书架行为模型：**

```
根目录/
├── 漫画系列1/          → COMIC_BOOK 类型
│   ├── chapter1.pdf
│   ├── chapter2.pdf
│   └── chapter3.cbz
├── 漫画系列2/          → COMIC_BOOK 类型
│   └── ...
├── standalone.pdf     → PDF 类型（独立文件）
└── comic.cbz          → CBZ 类型（独立文件）
```

**设计要点：**
- 子目录视为"漫画系列"（COMIC_BOOK），内部文件为分卷/章节
- 独立支持文件直接显示为单文件漫画
- 使用 `FolderParser.compareNames()` 进行自然排序
- 通过 `pathStack` 和 URI 字符串比较管理目录层级，防止循环嵌套

#### BookshelfScreen [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/bookshelf/BookshelfScreen.kt)

书架 UI 界面，展示漫画卡片网格。

**功能特性：**
- 网格布局展示漫画封面
- 顶部横向滚动展示"最近阅读"
- 支持筛选（全部/收藏/未读/阅读中/已读）和排序（名称/最近阅读）
- 长按收藏/取消收藏、分类、详情、批量操作
- 空状态引导选择目录
- 使用 Coil 加载封面图片

---

### 4.3 文件浏览器模块 (`browser`)

#### BrowserViewModel [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/browser/BrowserViewModel.kt)

文件浏览器数据管理，支持本地存储和 SAF 浏览。

**核心函数：**

| 函数 | 功能 |
|------|------|
| `openFolder(uri)` | 打开指定文件夹 |
| `navigateUp()` | 返回上级目录 |
| `refreshCurrentDir()` | 刷新当前目录 |
| `listRootStorage()` | 列出根目录存储 |
| `listFileFolder(folder)` | 列出 file:// 文件夹内容 |
| `listDocumentFolder(uri)` | 列出 content:// SAF 文件夹内容 |

**目录历史管理：** 使用 `history` 列表维护浏览历史，支持返回操作。

#### BrowserScreen [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/browser/BrowserScreen.kt)

文件浏览器 UI 界面。

**功能特性：**
- 权限申请处理（Android 10/11+ 不同策略）
- 文件/文件夹列表展示
- 支持"打开为漫画"（将文件夹作为图片序列）
- SAF 浏览入口

---

### 4.4 分卷浏览模块 (`chapters`)

#### ChapterBrowserScreen [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/chapters/ChapterBrowserScreen.kt)

漫画分卷浏览界面。点击书架中的漫画后，先进入分卷浏览而非直接阅读。

**功能特性：**
- 网格展示每一分卷的封面缩略图
- 显示分卷标题与页数（如 "第1卷  24P"）
- 点击分卷进入阅读器，并自动跳转至上一次阅读页
- 使用漫画领域专业术语：分卷列表、上一卷/下一卷、P 表示页数

#### ChapterBrowserViewModel [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/chapters/ChapterBrowserViewModel.kt)

分卷浏览数据管理，负责解析漫画并提取分卷/封面信息。

**核心逻辑：**
- 解析完整页面列表后，按 `chapterTitle` 分组为 `ComicChapter`
- 分组完成后使用 `FolderParser.compareNames()` 按分卷名称自然排序
- 解决文件顺序导致的分卷列表混乱问题

----
---

### 4.5 阅读器模块 (`reader`)

#### ReaderViewModel [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/reader/ReaderViewModel.kt)

阅读器核心逻辑，管理页面加载、设置和状态。

**核心状态 - ReaderUiState：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `isLoading` | Boolean | 是否正在加载 |
| `title` | String | 漫画标题 |
| `currentPage` | Int | 当前页码 |
| `totalPages` | Int | 总页数 |
| `showMenu` | Boolean | 是否显示菜单 |
| `settings` | ReaderSettings | 阅读设置 |
| `error` | String? | 错误信息 |
| `bookmarks` | List<Int> | 书签列表 |
| `currentChapter` | String | 当前分卷标题 |
| `settingsVersion` | Int | 设置版本，用于刷新 |

**核心函数：**

| 函数 | 功能 |
|------|------|
| `loadComic(entry, chapterPages)` | 加载漫画，解析页面列表 |
| `nextPage()` | 下一页（考虑双页模式） |
| `previousPage()` | 上一页（考虑双页模式） |
| `setCurrentPage(page)` | 设置当前页、保存进度，并触发方向感知预加载 |
| `loadPageBitmap(index)` | 加载并处理页面位图 |
| `preloadAround(page)` | 预加载周围页面（已整合到 `setCurrentPage`） |
| `startAutoPage(seconds)` | 启动自动翻页 |
| `toggleBookmark()` | 切换当前页书签 |
| `getChapterList()` | 计算分卷列表，按名称自然排序 |
| `enterPanelView()` / `exitPanelView()` | 进入/退出分镜模式 |
| `updateSettings(transform)` | 更新设置并持久化 |

**图片处理流程：**
```
原始页面加载 → 自动裁白边(autoCrop) → 应用滤镜(亮度/对比度/Gamma/饱和度/旋转/灰度/锐化/降噪) → 返回Bitmap
```

**内存管理：**
- 使用 `PageCacheManager` 维护大范围内存缓存窗口（当前页前后各 15 页，约 31 页）
- 滚动模式下根据翻阅方向优先预加载，避免不按顺序加载
- 处理后位图仅缓存少量最近页（默认 3 张）
- 退出阅读器时立即释放内存缓存，保留 LRU 磁盘缓存

#### ReaderScreen [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/reader/ReaderScreen.kt)

阅读器 UI 界面，采用「内容优先，UI 隐形」设计。

**阅读模式：**

| 模式 | 组件 | 说明 |
|------|------|------|
| PAGE | PageViewer | 单页/双页模式，支持缩放 |
| VERTICAL | VerticalScrollViewer | 垂直滚动（条漫） |
| HORIZONTAL | HorizontalScrollViewer | 水平滚动 |

**交互特性：**
- 默认全屏零 UI，轻触中央呼出半透明控制栏
- 点击左右热区翻页（支持 RTL 日漫模式）
- 双击缩放，双指缩放与平移
- 左边缘滑动返回
- 屏幕左侧上下滑动调节亮度
- 音量键翻页（可选）
- 沉浸式全屏

**菜单功能：**
- 顶部显示漫画标题、分卷与页码（如 "第1卷  5/24P"）
- 底部细进度条，拖动时显示缩略图预览
- 上一页/下一页快速跳转
- 设置入口（跳转独立设置页）

---

### 4.6 设置模块 (`settings`)

#### SettingsScreen [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/settings/SettingsScreen.kt)

独立设置页，将阅读设置从阅读器菜单中剥离，避免阅读时 UI 繁杂。

**设置分组：**
- 阅读设置：翻页方式、RTL、双页模式、自动翻页、音量键翻页
- 图像设置：缩放算法、亮度、对比度、Gamma、饱和度、自动裁边、锐化、降噪
- 手势与按键：点击区域大小、手势映射
- 书库设置：默认目录、扫描规则
- 外观主题：深色/浅色、主题皮肤、背景纹理
- 关于：版本号、检查更新、开源声明

#### MangaReaderTheme [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/ui/theme/MangaReaderTheme.kt)

应用级主题皮肤系统，基于 Material3 动态生成暗色/亮色配色方案。

**预设主题：**

| 主题 | displayName | 是否深色 | 强调色 | 阅读背景 | 背景纹理 |
|------|-------------|----------|--------|----------|----------|
| INK | 经典黑 | 是 | 0xFF5C7A94 | #FF121212 | NONE |
| KRAFT | 牛皮纸 | 否 | 0xFF8D6E63 | #FFF5E6C8 | KRAFT |
| CYBER | 赛博朋克 | 是 | 0xFF00BCD4 | #FF0A0A14 | GLASS |
| EYE_CARE | 护眼绿 | 否 | 0xFF4CAF50 | #FFE8F5E9 | NONE |
| WINE | 酒红幕 | 是 | 0xFF880E4F | #FF1A0A10 | NONE |

---

### 4.7 数据源抽象模块 (`provider`)

#### ComicProvider [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/provider/ComicProvider.kt)

统一漫画数据源抽象接口。本地文件、压缩包、PDF、EPUB、网络文件（SMB/FTP/云盘）以及未来插件都通过此接口向阅读器提供页面，上层不关心数据来源。

```kotlin
interface ComicProvider : AutoCloseable {
    val title: String
    val pageCount: Int
    suspend fun getPage(index: Int): Bitmap?
    suspend fun getPageStream(index: Int): InputStream? = null
    suspend fun getThumbnail(index: Int, maxDimension: Int): Bitmap?
    fun getPageName(index: Int): String = ""
    fun getChapterTitle(index: Int): String = ""
    override fun close() {}
}
```

**主要实现：**

| 实现类 | 用途 |
|--------|------|
| `LocalFolderProvider` | 本地文件夹图片序列 |
| `ZipArchiveProvider` | ZIP/CBZ 压缩包随机读取 |
| `ParserBasedComicProvider` | 适配现有 Parser 体系到 Provider 接口 |
| `SmbProvider` / `NetworkComicProvider` | SMB/NAS 网络直读（扩展中） |
| `PluginComicProvider` | 插件化数据源（预留） |

---

### 4.8 缓存模块 (`cache`)

#### PageCacheManager [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/cache/PageCacheManager.kt)

页面缓存管理器，核心设计目标是在保持翻页流畅的前提下，将内存占用压到最低（接近 Perfect Viewer 级别）。

**缓存架构：**

```
┌─────────────────────────────────────────────┐
│                 L1 内存缓存                  │
│  以当前页为中心的动态窗口：前后各 15 页         │
│  退出阅读 / 切换漫画时清空，避免跨会话占用      │
│  内存占用 ≈ 31 × 单页采样后大小（按需加载）     │
└──────────────────┬──────────────────────────┘
                   │ 未命中
                   ▼
┌─────────────────────────────────────────────┐
│                 L2 磁盘缓存                  │
│  缓存原始图片字节，LRU 淘汰                   │
│  页面缓存 30MB + 缩略图缓存 10MB              │
└──────────────────┬──────────────────────────┘
                   │ 未命中
                   ▼
┌─────────────────────────────────────────────┐
│              ComicProvider                   │
│  本地文件 / 压缩包随机解压 / PDF 渲染 / 网络   │
└─────────────────────────────────────────────┘
```

**关键策略：**

| 策略 | 说明 |
|------|------|
| 大范围预加载 | 翻到第 N 页时，内存缓存 N-15 ~ N+15；按距离/滚动方向优先级加载 |
| 方向感知预加载 | 滚动模式下按 FORWARD/BACKWARD 顺序加载，避免视觉上的顺序错乱 |
| 并发控制 | 后台解码限制 3 个并发，平衡速度与内存峰值 |
| inSampleSize 采样 | 按屏幕尺寸 1.5 倍动态计算采样率，不加载全分辨率 |
| RGB_565 | 不透明图片使用 RGB_565，相比 ARGB_8888 省一半内存 |
| LRU 磁盘缓存 | 页面 30MB + 缩略图 10MB，超出时淘汰最久未使用 |
| 缩略图独立缓存 | 最大边不超过 180px，避免全分辨率解码 |
| onLowMemory | 系统内存紧张时仅保留当前页 |
| 退出清理 | `clearMemory()` 在阅读器退出 / 切换漫画时释放所有内存位图 |

#### DiskLruCache [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/cache/DiskLruCache.kt)

基于 LinkedHashMap 的简单 LRU 磁盘缓存，支持设置大小上限、按 key 读写、清理全部缓存。

---

### 4.9 解析器模块 (`parser`)

#### ComicParser [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/parser/ComicParser.kt)

解析器接口定义。

```kotlin
interface ComicParser {
    suspend fun parse(context: Context, uri: Uri): ParserResult
}

sealed class ParserResult {
    data class Success(val pages: List<ComicPage>, val type: ComicType) : ParserResult()
    data class Error(val message: String) : ParserResult()
}
```

#### ParserFactory [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/parser/ParserFactory.kt)

解析器工厂，负责创建解析器和检测文件类型。

| 函数 | 功能 |
|------|------|
| `getParser(type)` | 根据类型创建对应解析器 |
| `detectType(name)` | 根据文件名检测漫画类型 |

**支持格式映射：**

| 文件扩展名 | ComicType | 解析器 |
|-----------|-----------|--------|
| `.cbz`, `.zip` | CBZ | CbzParser |
| `.cbr`, `.rar` | CBR | CbrParser |
| `.cb7`, `.7z` | CB7 | Cb7Parser |
| `.cbt`, `.tar` | CBT | CbtParser |
| `.epub` | EPUB | EpubParser |
| `.pdf` | PDF | PdfParser |
| 文件夹 | FOLDER | FolderParser |
| 漫画系列目录 | COMIC_BOOK | ComicBookParser |

#### 各解析器实现

**CbzParser** [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/parser/CbzParser.kt)
- 使用 Java 内置 `ZipFile` 读取 ZIP 格式
- **随机读取**：只解析中央目录，按页按需解压，不全量解压
- 本地 file:// URI 直接访问原文件，避免临时文件复制

**CbrParser** [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/parser/CbrParser.kt)
- 使用 `junrar` 库解析 RAR 格式
- 需要将 content:// URI 复制到临时文件（junrar 不支持 InputStream）
- 本地 file:// URI 直接访问，减少复制开销

**Cb7Parser** [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/parser/Cb7Parser.kt)
- 使用 `commons-compress` 库解析 7z 格式
- 需要临时文件

**CbtParser** [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/parser/CbtParser.kt)
- 使用 `commons-compress` 库解析 TAR 格式
- 流式读取

**EpubParser** [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/parser/EpubParser.kt)
- 解析 EPUB 内部结构（container.xml → opf → spine）
- 按 spine 顺序提取图片页面
- 回退方案：按文件名排序

**PdfParser** [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/parser/PdfParser.kt)
- 使用 Android 内置 `PdfRenderer`（API 21+）
- 避免 pdfium-android JNI 问题
- 需要临时文件（PdfRenderer 要求 FileDescriptor）
- 支持最大像素限制（4096×4096），自动缩放

**FolderParser** [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/parser/FolderParser.kt)
- 支持 file:// 和 content:// 协议
- 扫描图片文件（jpg/jpeg/png/webp/gif/heic/bmp）
- **自然排序**：支持数字排序（chapter2 < chapter10）

**ComicBookParser** [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/parser/ComicBookParser.kt)
- 将目录视为漫画系列，子文件为分卷/章节
- 遍历分卷文件，使用对应解析器解析每卷
- 合并所有分卷页面为单一页面列表
- 增加 `MAX_DEPTH=4` 和已访问 URI 集合，防止无限嵌套

---

### 4.10 数据模块 (`data`)

#### Models [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/data/Models.kt)

**ComicType 枚举：**
```kotlin
enum class ComicType {
    CBZ, CBR, CB7, CBT, EPUB, PDF, FOLDER, COMIC_BOOK, UNKNOWN
}
```

**ComicEntry 数据类：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `uri` | Uri | 文件/目录的 URI |
| `title` | String | 标题（文件名） |
| `type` | ComicType | 漫画类型 |
| `coverUri` | Uri? | 封面图片 URI |
| `path` | String | 文件路径 |
| `lastReadPage` | Int | 上次阅读页码 |
| `totalPages` | Int | 总页数 |
| `isDirectory` | Boolean | 是否为目录 |

**ComicPage 数据类：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `index` | Int | 页面索引 |
| `name` | String | 页面名称 |
| `load` | suspend () -> Bitmap? | 页面加载函数（懒加载） |

**ComicChapter 数据类：**
```kotlin
data class ComicChapter(
    val title: String,
    val uri: Uri,
    val type: ComicType,
    val pages: List<ComicPage>
)
```

#### SettingsRepository [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/data/SettingsRepository.kt)

基于 DataStore Preferences 的设置持久化仓库。

**ReaderSettings 数据类（完整字段）：**

| 类别 | 字段 | 类型 | 默认值 | 说明 |
|------|------|------|--------|------|
| 布局 | dualPage | Boolean | false | 双页模式 |
| 布局 | rtl | Boolean | true | 从右向左翻页 |
| 布局 | immersive | Boolean | true | 沉浸式全屏 |
| 布局 | backgroundColor | String | "#FF000000" | 阅读背景色 |
| 布局 | backgroundTexture | BackgroundTexture | NONE | 背景纹理 |
| 布局 | dualPageSpacing | Int | 0 | 双页间距 |
| 布局 | dualPageOffset | Int | 0 | 双页偏移 |
| 布局 | dualPageStartOne | Boolean | false | 双页从单页开始 |
| 布局 | pageShadow | Boolean | false | 页面阴影 |
| 布局 | mirror | Boolean | false | 镜像 |
| 布局 | pageAnimation | PageAnimation | NONE | 翻页动画 |
| 布局 | randomAnimation | Boolean | false | 随机动画 |
| 布局 | tapZoneSize | TapZoneSize | MEDIUM | 点击热区大小 |
| 布局 | volumeKeyNav | Boolean | false | 音量键翻页 |
| 滤镜 | autoCrop | Boolean | false | 自动裁白边 |
| 滤镜 | brightness | Float | 1f | 亮度 |
| 滤镜 | contrast | Float | 1f | 对比度 |
| 滤镜 | gamma | Float | 1f | Gamma |
| 滤镜 | saturation | Float | 1f | 饱和度 |
| 滤镜 | rotation | Int | 0 | 旋转角度 |
| 滤镜 | grayscale | Boolean | false | 灰度 |
| 滤镜 | sharpen | Boolean | false | 锐化 |
| 滤镜 | sharpenStrength | Float | 1f | 锐化强度 |
| 滤镜 | denoise | Boolean | false | 降噪 |
| 滤镜 | denoiseStrength | Float | 1f | 降噪强度 |
| 滤镜 | nightMode | Boolean | false | 夜间模式 |
| 滤镜 | eyeCare | Boolean | false | 护眼模式 |
| 滤镜 | scaleFilter | ScaleFilter | BILINEAR | 缩放算法 |
| 模式 | scrollMode | ScrollMode | PAGE | 滚动模式 |
| 模式 | zoomMode | ZoomMode | FREE | 缩放模式 |
| 模式 | enableZoom | Boolean | false | 启用缩放 |
| 模式 | magnifierEnabled | Boolean | false | 对话框放大镜 |
| 自动翻页 | autoPageInterval | Int | 0 | 自动翻页间隔秒数 |
| 主题 | appTheme | AppTheme | INK | 应用主题皮肤 |

**枚举类型：**

```kotlin
enum class ScrollMode { PAGE, VERTICAL, HORIZONTAL }
enum class ZoomMode { FREE, FIT_WIDTH, FIT_SCREEN }
enum class PageAnimation { NONE, SLIDE, CURL, FADE, PIXEL, BLINDS, SHATTER, DOTS }
enum class BackgroundTexture { NONE, KRAFT, GLASS, STARS, WOOD }
enum class TapZoneSize { SMALL, MEDIUM, LARGE }
enum class ScaleFilter { BILINEAR, BICUBIC, LANCZOS3 }
```

**持久化数据结构：**

| Key 前缀 | 用途 |
|----------|------|
| `progress_{uri}` | 阅读页码 |
| `progress_time_{uri}` | 阅读时间戳 |
| `total_{uri}` | 总页数 |
| `bookmarks_map` | 书签映射（JSON） |
| `favorites_set` | 收藏集合 |
| `categories_list` | 分类列表 |
| `comic_categories` | 漫画分类映射 |
| `reading_lists` | 用户书单 |
| `reading_stats` | 阅读统计 |
| `extras_{uri}` | 漫画评分/标签/短评 |
| `bookshelf_root` | 书架根目录 URI |

---

### 4.11 工具模块 (`utils`)

#### ImageUtils [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/utils/ImageUtils.kt)

**核心函数：**

| 函数 | 功能 |
|------|------|
| `autoCropWhiteBorders(src)` | 自动裁剪白色边框 |
| `applyAdjustments(...)` | 应用亮度/对比度/Gamma/饱和度/灰度/旋转 |
| `buildColorMatrix(...)` | 构建颜色矩阵 |
| `applySharpen(src, strength)` | 应用锐化滤镜 |
| `applyDenoise(src, strength)` | 应用降噪滤镜 |
| `rotateBitmap(src, degrees)` | 旋转位图 |
| `scaleBitmap(src, scale, filter)` | 按指定算法缩放位图 |

**颜色矩阵处理流程：**
1. 灰度化（可选）
2. 对比度调整
3. 亮度调整
4. Gamma 校正
5. 饱和度调整

#### PanelDetector [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/utils/PanelDetector.kt)

分镜检测工具，自动识别漫画页面中的分镜区域，支持分镜阅读模式。

#### PermissionUtils [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/utils/PermissionUtils.kt)

存储权限申请与检查，适配 Android 10/11+ 不同策略。

#### 其他工具

| 文件 | 用途 |
|------|------|
| `CoverExtractor.kt` | 封面提取 |
| `FileManager.kt` | 文件操作辅助 |
| `FileListHelper.kt` | 文件列表与自然排序 |
| `MetadataScraper.kt` | 漫画元数据刮削 |
| `OnboardingUtils.kt` | 引导页逻辑 |
| `WebDavSync.kt` | WebDAV 同步 |
| `SmbClient.kt` | SMB 客户端 |
| `ExternalDisplayCast.kt` | 外部显示器投屏 |
| `WindowSize.kt` | 窗口尺寸适配 |

---

## 5. 依赖关系图

### 5.1 模块依赖

```
MainActivity
    │
    └── MangaReaderApp
            │
            ├── BookshelfScreen ─── BookshelfViewModel
            │                           │
            │                           ├── SettingsRepository
            │                           └── ParserFactory, FolderParser
            │
            ├── BrowserScreen ─── BrowserViewModel
            │                          │
            │                          └── ParserFactory
            │
            ├── ChapterBrowserScreen ─── ChapterBrowserViewModel
            │                                  │
            │                                  └── ParserFactory, ComicProviderFactory
            │
            ├── ReaderScreen ─── ReaderViewModel
            │                          │
            │                          ├── SettingsRepository
            │                          ├── PageCacheManager
            │                          └── ComicProvider / ImageUtils
            │
            └── SettingsScreen ─── SettingsRepository
```

### 5.2 解析器依赖

```
ParserFactory
    │
    ├── CbzParser (ZipFile 随机读取)
    ├── CbrParser (junrar)
    ├── Cb7Parser (commons-compress)
    ├── CbtParser (commons-compress)
    ├── EpubParser (XmlPullParser)
    ├── PdfParser (Android PdfRenderer)
    ├── FolderParser
    └── ComicBookParser ─── ParserFactory (递归调用)
```

### 5.3 缓存与数据流

```
ReaderViewModel
    │
    ├── PageCacheManager (大范围内存窗口 + LRU 磁盘)
    │          │
    │          └── ComicProvider.getPageStream / getPage
    │
    └── ImageUtils (滤镜处理)
               │
               └── ReaderScreen 显示
```

### 5.4 外部依赖

| 依赖 | 用途 |
|------|------|
| `androidx.navigation:navigation-compose` | 页面导航 |
| `androidx.datastore:datastore-preferences` | 设置持久化 |
| `io.coil-kt:coil-compose` | 图片加载 |
| `com.github.junrar:junrar` | RAR 解压 |
| `org.apache.commons:commons-compress` | 7z/TAR 解压 |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | ViewModel 集成 |
| `kotlinx-coroutines-android` | 协程支持 |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | JSON 序列化 |

---

## 6. 项目运行方式

### 6.1 开发环境要求

- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17
- Android SDK 34（编译）/ 26（最低）

### 6.2 构建命令

项目使用本地 Gradle 8.4 分发版（`gradle-8.4/`）：

```bash
# 构建 debug APK
.\gradle-8.4\bin\gradle.bat assembleDebug --no-daemon -p app

# 构建 release APK
.\gradle-8.4\bin\gradle.bat assembleRelease --no-daemon -p app

# 运行到设备
.\gradle-8.4\bin\gradle.bat installDebug --no-daemon -p app

# 清理构建缓存
.\gradle-8.4\bin\gradle.bat clean --no-daemon -p app
```

### 6.3 调试运行

1. 打开 Android Studio
2. 导入项目目录
3. 等待 Gradle 同步完成
4. 选择模拟器或连接真实设备
5. 点击 Run 按钮

### 6.4 APK 安装

构建成功后，APK 文件位于：
```
app/build/outputs/apk/debug/app-debug.apk
```

**注意：** 已通过 `gradle.properties` 与 `AndroidManifest.xml` 关闭 `testOnly="true"`，debug APK 可直接通过 `adb install` 或文件管理器在真机上安装，无需 `-t` 参数。

---

## 7. 关键设计决策

### 7.1 大范围内存预加载缓存

缓存策略从固定 3 张升级为以当前页为中心的动态窗口：
- 翻到第 N 页时，预加载并缓存 N-15 ~ N+15 页
- 后台按距离当前页由近及远解码；滚动模式下按翻阅方向优先顺序加载
- 退出阅读器或切换漫画时调用 `clearMemory()` 释放所有内存位图，保留 LRU 磁盘缓存
- 并发解码限制为 3，兼顾加载速度与内存峰值

### 7.2 压缩包随机读取

CBZ/CBR 等压缩包不再全量解压到磁盘，而是：
- 解析压缩包目录索引（HashMap，几乎不占内存）
- 看第 N 页时 seek 到对应位置，只解压该页
- 显著减少首次打开时间和磁盘缓存占用

### 7.3 图像解码优化

- 使用 `inSampleSize` 按屏幕尺寸 1.5 倍动态采样
- 不透明图片使用 `RGB_565`，相比 ARGB_8888 省一半内存
- 缩略图使用独立缓存体系，最大边不超过 180px
- 处理后位图仅缓存当前页 1 张

### 7.4 懒加载页面

所有解析器返回的 `ComicPage` 包含一个 `load: suspend () -> Bitmap?` 函数，页面图片仅在实际显示时才加载，节省内存和启动时间。

### 7.5 PDF 解析方案

选择 Android 内置的 `PdfRenderer` 而非第三方库（如 pdfium-android），原因：
- pdfium-android 在 Android 11+ 上对 content:// URI 存在兼容性问题
- PdfRenderer 是系统级 API，稳定性更好
- 需要将 URI 复制到临时文件，但避免了 JNI 问题

### 7.6 自然排序

使用 `FolderParser.naturalSortKey()` 和 `compareNames()` 实现自然排序，确保 `chapter2 < chapter10`，而非字典序 `chapter10 < chapter2`。

**应用场景：**
- 书架/浏览器文件列表排序
- 分卷浏览网格按分卷名称排序
- 阅读器内「分卷列表」按分卷名称排序

### 7.7 SAF 支持

同时支持传统文件系统（file://）和 Storage Access Framework（content://），适配 Android 11+ 的存储权限模型。

### 7.8 状态管理

使用 `StateFlow` + `collectAsStateWithLifecycle()` 实现响应式状态管理，遵循 MVVM 架构模式。

### 7.9 内容优先，UI 隐形

- 阅读器默认全屏，不显示任何控件
- 轻触中央才呼出半透明控制栏
- 设置独立成页，不占用阅读界面空间
- 页码只在呼出菜单时显示

### 7.10 防止无限嵌套

- `ComicBookParser` 设置 `MAX_DEPTH=4` 并记录已访问 URI，防止子目录循环
- `BookshelfViewModel` 使用 `pathStack` 和 URI 字符串比较管理层级

### 7.11 关闭 testOnly

为方便真机直接安装测试：
- `gradle.properties` 添加 `android.injected.testOnly=false`
- `AndroidManifest.xml` 中 `<application>` 显式声明 `android:testOnly="false"` 并使用 `tools:replace` 确保最终合并值生效
- 生成的 debug APK 不再携带 testOnly 标记，可直接安装

---

## 8. UI 设计规范

### 8.1 设计原则

- **内容优先**：漫画画面是主角，UI 不抢风头
- **极简 + 质感**：深色系为主，低饱和度强调色
- **控件按需出现**：默认隐藏，需要时才呼出
- **圆角与间距**：12-16dp 圆角，网格间距紧凑

### 8.2 阅读界面热区

```
┌──────────┬──────────┬──────────┐
│          │          │          │
│  上一页   │  呼出菜单 │  下一页   │
│  (1/3)   │  (1/3)   │  (1/3)   │
│          │          │          │
└──────────┴──────────┴──────────┘
```

- RTL（日漫）模式下左右热区功能互换
- 热区大小可通过 `TapZoneSize` 调整

### 8.3 主题皮肤

预置 5 套主题，覆盖深色/浅色、不同阅读场景：
- 经典黑（默认，耐看）
- 牛皮纸（复古感）
- 赛博朋克（高对比科技感）
- 护眼绿（长时间阅读）
- 酒红幕（低饱和深色）

---

## 9. 扩展建议

### 9.1 性能优化
- 大图分区解码（只解码可视区域）
- 渐进式加载：先显示低分辨率预览，再替换高清图
- 解码并发控制，避免线程过多

### 9.2 功能扩展
- 插件化架构：PDF、网络协议拆分为可选插件
- SMB/FTP/云盘网络直读完善
- 漫画元数据书库（在文件系统优先基础上扩展）
- 搜索漫画内容与 OCR 文字识别

### 9.3 用户体验
- 更多翻页过渡动画
- 空状态插画
- 封面放大过渡到阅读界面的共享元素动画
- 成就解锁微交互动效

---

## 10. 文件索引

| 模块 | 文件 | 说明 |
|------|------|------|
| 入口 | MainActivity.kt | 应用入口 Activity |
| 入口 | MangaReaderApp.kt | Compose 导航宿主 |
| 书架 | BookshelfScreen.kt | 书架网格 UI |
| 书架 | BookshelfViewModel.kt | 书架数据管理 |
| 书架 | BookshelfExtras.kt | 书架扩展功能 |
| 浏览器 | BrowserScreen.kt | 文件浏览器 UI |
| 浏览器 | BrowserViewModel.kt | 浏览器数据管理 |
| 分卷 | ChapterBrowserScreen.kt | 分卷浏览 UI |
| 分卷 | ChapterBrowserViewModel.kt | 分卷浏览数据管理 |
| 阅读器 | ReaderScreen.kt | 阅读器 UI |
| 阅读器 | ReaderViewModel.kt | 阅读器逻辑 |
| 阅读器 | PageCurlEffect.kt | 翻页动画效果 |
| 设置 | SettingsScreen.kt | 独立设置页 |
| 主题 | MangaReaderTheme.kt | 主题皮肤系统 |
| 引导 | OnboardingHost.kt | 首次使用引导 |
| 数据 | Models.kt | 数据模型 |
| 数据 | SettingsRepository.kt | 设置持久化 |
| 数据 | ComicExtras.kt | 漫画额外信息 |
| 数据 | GestureConfig.kt | 手势映射配置 |
| 缓存 | PageCacheManager.kt | 三页内存 + 磁盘缓存 |
| 缓存 | DiskLruCache.kt | 磁盘 LRU 缓存 |
| 提供者 | ComicProvider.kt | 统一数据源接口 |
| 提供者 | ComicProviderFactory.kt | Provider 工厂 |
| 提供者 | LocalFolderProvider.kt | 本地文件夹提供者 |
| 提供者 | ZipArchiveProvider.kt | 压缩包提供者 |
| 提供者 | ParserBasedComicProvider.kt | Parser 适配提供者 |
| 提供者 | NetworkComicProvider.kt | 网络提供者 |
| 提供者 | SmbProvider.kt | SMB 提供者 |
| 提供者 | PluginComicProvider.kt | 插件提供者 |
| 解析器 | ComicParser.kt | 解析器接口 |
| 解析器 | ParserFactory.kt | 解析器工厂 |
| 解析器 | FolderParser.kt | 文件夹解析器 |
| 解析器 | ComicBookParser.kt | 漫画系列解析器 |
| 解析器 | CbzParser.kt | CBZ/ZIP 解析器 |
| 解析器 | CbrParser.kt | CBR/RAR 解析器 |
| 解析器 | Cb7Parser.kt | CB7/7z 解析器 |
| 解析器 | CbtParser.kt | CBT/TAR 解析器 |
| 解析器 | EpubParser.kt | EPUB 解析器 |
| 解析器 | PdfParser.kt | PDF 解析器 |
| 解析器 | PageLoader.kt | 页面加载辅助 |
| 工具 | ImageUtils.kt | 图像处理 |
| 工具 | PanelDetector.kt | 分镜检测 |
| 工具 | PermissionUtils.kt | 权限工具 |
| 工具 | CoverExtractor.kt | 封面提取 |
| 工具 | FileManager.kt | 文件管理 |
| 工具 | MetadataScraper.kt | 元数据刮削 |
| 工具 | WebDavSync.kt | WebDAV 同步 |
| 工具 | SmbClient.kt | SMB 客户端 |
| 工具 | OnboardingUtils.kt | 引导工具 |
| 配置 | build.gradle.kts | 应用构建配置 |
| 配置 | AndroidManifest.xml | 应用清单 |

---

*文档生成时间：2026-07-13*
*项目版本：1.2*
