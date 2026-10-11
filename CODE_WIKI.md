# MangaReader 漫画阅读器 - Code Wiki

## 1. 项目概述

MangaReader 是一款基于 Android Jetpack Compose 的本地漫画阅读器，采用「内容优先，UI 隐形」的设计理念，支持多种漫画格式，具备完整的书架管理、分卷浏览和沉浸式阅读功能。

**主要功能特性：**
- 支持 CBZ、CBR、CB7、CBT、EPUB、PDF、MOBI/AZW/AZW3、文件夹等多种漫画/电子书格式
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
| 切换漫画磁盘清理 | 切换漫画时仅 `clearByCacheId(oldCacheId)`，保留其他漫画的跨会话磁盘缓存 |
| 独立清页缓存 | `clearPageDisk()` 仅清除页面磁盘缓存（30MB），保留缩略图 |
| 独立清缩略图 | `clearThumbDisk()` 仅清除缩略图磁盘缓存（10MB），保留页面缓存 |
| 按漫画清理 | `clearByCacheId(cacheId)` 删除指定漫画的所有页面与缩略图缓存 |
| 缓存大小估算 | `getCacheSizeFor(cacheId)` 估算指定漫画占用的磁盘缓存字节数 |

**磁盘缓存索引维护：**
- `diskKeyIndex` 是磁盘缓存 key 的内存索引，仅用于加速 `clearByCacheId` / `getCacheSizeFor`
- key 必须在 `pageDiskCache.put()` / `thumbDiskCache.put()` **成功写入后**再加入索引，避免写入失败留下无效索引
- 详细修复见 [更新日志 13.8](#138-稳定性与线程安全修复-2026-07-21)

#### DiskLruCache [文件](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/cache/DiskLruCache.kt)

基于文件最后访问时间的简单 LRU 磁盘缓存，支持设置大小上限、按 key 读写、清理全部缓存。

**关键策略：**

| 策略 | 说明 |
|------|------|
| 读写锁隔离 | 元数据操作使用 `ReentrantReadWriteLock`，读操作不阻塞其他读操作 |
| 写入不阻塞读取 | `put()` 先将数据写入临时文件，再获取写锁执行原子重命名，避免大文件写入期间阻塞读取 |
| 内存访问时间索引 | 使用 `ConcurrentHashMap` 维护最近访问时间，避免依赖文件系统 `lastModified()` 精度 |
| 后台 trim | 超出容量上限时由单线程后台异步删除最久未访问条目 |
| 崩溃安全 | 使用 `.tmp` 临时文件 + 重命名，写入中断不会破坏已存在的缓存文件 |

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

### 7.12 协程取消与状态保存

**CancellationException 必须显式传播：**
- 所有 `try/catch (e: Exception)` 块中，单独捕获 `CancellationException` 并 `throw e`
- 禁止用 `runCatching { }` 包裹会触发协程取消的代码，避免吞掉取消信号
- 典型场景：WebDAV 网络请求、Worker `doWork()`、DataStore 读取流

**ViewModel 销毁时的持久化：**
- 阅读进度、书签、阅读时长结算等关键持久化操作，必须使用 `applicationScope + NonCancellable`
- `viewModelScope` 在 `onCleared()` 时会被取消，不能用于退出时的兜底保存
- `ReaderViewModel.releaseCache()` 与 `onCleared()` 通过 `AtomicBoolean` 保证会话只结算一次

**UI 状态更新线程安全：**
- `MutableStateFlow.value` 的写入不强制要求主线程，但为保持一致性，所有 `_uiState.value = ...` 统一在主线程执行
- `BookshelfViewModel` 中所有状态写入使用 `withContext(Dispatchers.Main)` 包装

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
---

## 13. 更新日志 (2026-07-14)

### 13.1 阅读体验优化

#### 卷末自动跳转提示
- 阅读到本卷最后一页时，自动弹出对话框
- 若存在下一卷，显示"跳转下一卷"按钮
- 若已是最后一卷，显示"已是最后一卷"提示
- 卷册按名称自动排序，不区分已读/未读

**相关文件：**
- ReaderScreen.kt - 卷末对话框 UI
- ReaderViewModel.kt - checkChapterEnd(), getNextChapterTitle(), jumpToNextChapter()

#### 翻页动画速度控制
- 新增 pageAnimSpeed 设置项 (0.2x - 3.0x)
- 支持滑块调节翻页动画速度
- "无动画"模式下即时切换

**相关文件：**
- SettingsRepository.kt - pageAnimSpeed 持久化
- ReaderScreen.kt - 动画持续时间根据速度倍率动态计算

#### 轻拂（Fling）手势检测
- 新增速度追踪机制
- 快速轻拂即可翻页，无需滑过 100px 阈值
- 支持 RTL 日漫模式

**相关文件：**
- ReaderScreen.kt - detectHorizontalDragGestures 中集成速度计算

#### 退出阅读确认
- 按返回键时弹出确认对话框
- 防止误触丢失阅读位置
- 阅读进度已自动保存

**相关文件：**
- ReaderScreen.kt - showExitConfirm 对话框

#### 智能书签自动保存
- 退出阅读时自动创建恢复书签
- 仅在已读 > 3 页且当前页无书签时触发
- 避免用户忘记手动书签

**相关文件：**
- ReaderViewModel.kt - utoSaveBookmarkOnExit()

### 13.2 封面提取优化

#### 封面并行提取
- 使用 ParallelCoverExtractor 并行解码多张封面
- 书架界面加载速度提升约 50%
- 支持文件夹类型封面的缩略图生成

**相关文件：**
- ParallelCoverExtractor.kt - 并行提取工具
- BookshelfViewModel.kt - extractCovers() 使用并行提取
- CoverExtractor.kt - extractLocalImageThumbnail() 本地图片缩略图

#### 文件夹类型封面修复
- FOLDER 类型封面现在通过 CoverExtractor.extractLocalImageThumbnail() 生成缩略图
- 避免直接加载大图导致的内存问题
- 支持缓存，避免重复解码

### 13.3 动画稳定性修复

#### 翻页动画 Key 稳定性
- 使用 nimationKey (Pair<currentPage, effectiveAnimation>) 作为 AnimatedContent 的 targetState
- 避免随机动画模式下动画状态混乱
- 支持单页、双页、RTL 等所有阅读模式

**相关文件：**
- ReaderScreen.kt - AnimatedContent targetState 使用稳定 key

### 13.4 新增功能

#### GitHub Star 请求入口
- 书架右上角菜单新增"GitHub Star"选项
- 显示项目链接，支持复制和跳转浏览器
- 仓库地址：https://github.com/believery06/BestComic

**相关文件：**
- GitHubStarDialog.kt - Star 请求对话框
- BookshelfScreen.kt - 菜单集成

#### 打赏功能入口
- 书架右上角菜单新增"打赏支持"选项
- 点击后显示"暂未开通"提示

**相关文件：**
- DonationDialog.kt - 打赏提示对话框

#### 错误日志一键复制
- 崩溃日志弹窗增加"复制日志"按钮
- 一键复制完整堆栈到剪贴板
- 便于用户反馈问题

**相关文件：**
- CrashLogDialog.kt - 复制按钮集成

### 13.5 性能优化

#### 智能预加载策略
- 新增 ScrollSpeedTracker 追踪滚动速度
- 动态调整预加载范围：快速滑动时 25 页，慢速时 8 页
- 支持 setAggressiveness() 手动设置预加载强度

**相关文件：**
- PageCacheManager.kt - ScrollSpeedTracker, setAggressiveness()

#### L2 磁盘缓存内存索引
- 新增 diskKeyIndex: Set<String> 内存索引
- 避免每次读取都查询文件系统
- 提升缓存查找速度

#### 加载任务可取消
- prefetchRange 和 loadIntoMemory 中增加 isActive 检查
- 翻页时自动取消旧的预加载任务
- 减少不必要的 CPU 和内存消耗

#### 预加载方向感知
- 新增 PrefetchDirection 枚举
- 优先加载用户翻页方向的页面
- 提升预加载命中率

#### 大图分块解码
- 超过 8000px 的图片使用 BitmapRegionDecoder 分块加载
- 有效防止超长条漫 OOM
- 自动启用，无需用户设置

#### SupervisorJob 隔离
- 预加载协程使用 SupervisorJob()
- 单页加载失败不会中断其他页面的预加载
- 提升预加载稳定性

#### 预加载信号量控制
- 新增 Semaphore(PREFETCH_CONCURRENCY) 限制并发
- 避免过多线程争抢 CPU
- 默认并发数为 3

### 13.6 稳定性增强

#### 全局异常捕获
- CrashHandler 捕获所有未处理异常
- 写入日志文件到 ilesDir/crash_logs/
- 下次启动时弹出错误日志对话框

**相关文件：**
- CrashHandler.kt - 异常捕获和日志管理
- MangaReaderApplication.kt - 初始化 CrashHandler
- AndroidManifest.xml - 注册 Application 类

#### 临时文件清理
- 应用启动时清理超过 24 小时的旧 CBT/CB7 临时目录
- 防止进程被杀导致的临时文件泄漏

**相关文件：**
- MangaReaderApplication.kt - cleanOldTempFiles()

#### BitmapConfig 优化
- 非透明图片使用 RGB_565 配置
- 减少 50% 内存占用
- 自动检测图片透明度

### 13.7 设置项新增

| 设置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| pageAnimSpeed | Float | 1.0f | 翻页动画速度倍率 (0.2f - 3.0f) |
| KEY_PAGE_ANIM_SPEED | Preferences Key | - | DataStore 存储键名 |

---

## 14. 翻页动画全面适配 (2026-07-14)

### 14.1 横屏模式自动适配

#### SLIDE 滑动动画
- 横屏时自动切换为垂直滑动
- 竖屏时使用水平滑动（支持 RTL 日漫）
- 通过检测容器宽高比自动判断

**相关文件：**
- `ReaderScreen.kt` - `isLandscape` 检测逻辑
- `PageCurlEffect.kt` - 支持垂直拖拽

#### CURL 3D 仿真翻页
- 横屏时使用垂直拖拽
- 竖屏时使用水平拖拽
- 翻页方向、阴影、书脊高光均适配横屏

### 14.2 动画参数扩展

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| isLandscape | Boolean | false | 横屏模式标识 |

### 14.3 内存管理优化

#### CURL 动画位图清理
- 新增 `DisposableEffect` 清理 CURL 动画位图
- 防止切换页面或退出阅读时内存泄漏

**相关文件：**
- `ReaderScreen.kt` - CURL 动画块中的 `DisposableEffect`

### 13.8 稳定性与线程安全修复 (2026-07-21)

#### PageCacheManager 磁盘缓存索引竞态修复
- `saveBytesToDisk()` 中 `addToDiskKeyIndex(key)` 从 `pageDiskCache.put()` 之前移至成功之后
- 避免写入失败时产生无效索引，保证 `clearByCacheId()` 和 `getCacheSizeFor()` 结果准确

**相关文件：**
- PageCacheManager.kt - `saveBytesToDisk()`

#### SettingsRepository.saveAsync 设置竞态修复
- 将 `readerSettings.first()` 从 `delay(300)` 之后提前到调用瞬间
- 300ms 延迟仅用于合并连续调用，不再引入新旧值混乱
- 避免滑块快速拖动时后一次写入覆盖前一次写入的错误结果

**相关文件：**
- SettingsRepository.kt - `saveAsync()`

#### 启动缓存清理不再破坏 DiskLruCache
- `CacheCleaner.cleanOnStartup()` 不再删除 `page_cache` / `thumb_cache` 目录
- 页面与缩略图磁盘缓存由 DiskLruCache 自身 LRU 策略管理，跨会话可复用
- 仅清理分享卡片临时 PNG 和解析器泄漏的超过 24 小时的临时文件/目录

**相关文件：**
- CacheCleaner.kt

#### WebDAV 同步异常处理强化
- `WebDavSync.checkRemoteExists()` 显式捕获并重新抛出 `CancellationException`
- `WebDavSyncWorker.doWork()` 中所有 DataStore 读取和同步操作单独处理取消异常
- Worker 被取消时返回 `Result.failure()`，避免系统反复调度已取消任务

**相关文件：**
- WebDavSync.kt
- WebDavSyncWorker.kt

#### Bitmap 生命周期修复
- `PanelDetector.detectPanels()` 使用 `try/finally` 确保缩放后的 Bitmap 在非 OOM 异常时也被回收
- `ParserBasedComicProvider.getThumbnail()` fallback 路径不再回收 provider 拥有的原始 Bitmap，仅回收本地创建的 `scaled`
- `PageCacheManager.clearMemory()` / `onLowMemory()` 调用 `BitmapPool.clear()`，防止复用池泄漏

**相关文件：**
- PanelDetector.kt
- ParserBasedComicProvider.kt
- PageCacheManager.kt

#### ReaderViewModel 退出保存可靠性
- `releaseCache()` 与 `onCleared()` 使用 `MangaReaderApplication.applicationScope + NonCancellable` 结算阅读时长、保存进度和书签
- 通过 `AtomicBoolean(readingSessionActive)` 保证会话只结算一次
- `setCurrentPage()` 与 TTS 触发的翻页在主线程更新 UI 状态

**相关文件：**
- ReaderViewModel.kt

#### BookshelfViewModel 状态更新线程安全
- 所有 `_uiState.value` 写入统一在主线程执行
- 封面批量更新使用 `withContext(Dispatchers.Main)` 一次提交，避免多次重组
- 加载任务使用 `loadGeneration` 丢弃旧任务结果，防止快速切换书架时状态被覆盖

**相关文件：**
- BookshelfViewModel.kt
- BookshelfScreen.kt

#### SettingsRepository.saveAsync 持久化作用域修复
- `saveAsync()` 不再依赖调用方传入的 UI `CoroutineScope`
- 新增 Repository 级 `repositoryScope`（`SupervisorJob + Dispatchers.IO`），确保用户在设置页拖动滑块后立即返回时，300ms 延迟写入仍能完成
- 新增 `flushSave()` 用于需要立即确认写入完成的场景

**相关文件：**
- SettingsRepository.kt
- SettingsScreen.kt

#### 阅读器性能与 EPUB 封面优化
- `ImageUtils.applyFilters()` 在亮度/对比度/Gamma/饱和度为默认值且未开启灰度/镜像/旋转时直接返回原图，避免默认状态下分配额外 Bitmap
- `CoverExtractor.extractEpubFirstImage()` 解析 EPUB `container.xml` → OPF → manifest / metadata，优先获取 `properties="cover-image"` 或 `<meta name="cover">` 指定的封面，再 fallback 到 zip 内第一张图片

**相关文件：**
- ImageUtils.kt
- CoverExtractor.kt

#### 缓存管理一键清理回归修复
- `CacheManagementScreen`「全部清理」按钮再次删除 `thumb_cache` 目录，破坏 DiskLruCache 日志与持久化状态
- 修复后统一调用 `PageCacheManager.getInstance(context).clear()`，由 DiskLruCache 同时清理页面与缩略图磁盘缓存

**相关文件：**
- CacheManagementScreen.kt

#### TTS 无文本页翻页线程安全修复
- `ReaderViewModel.speakCurrentPage()` 在 `Dispatchers.IO` 中发现当前页无文本时，直接调用 `nextPage()` 导致 `_uiState` 在后台线程更新
- 修复后使用 `withContext(Dispatchers.Main) { nextPage() }`，确保 UI 状态在主线程更新

**相关文件：**
- ReaderViewModel.kt

#### 阅读器退出时阅读设置兜底保存
- `ReaderViewModel.onCleared()` 仅取消 `settingsJob`，未在 ViewModel 销毁前保存 `_uiState.settings`
- 若用户在调整亮度/对比度等设置后 300ms 内退出阅读器，`settingsJob` 被取消，设置丢失
- 修复后在 `applicationScope + NonCancellable` 的退出协程中调用 `saveReaderSettings` 兜底保存最终设置

**相关文件：**
- ReaderViewModel.kt

#### CBT 解析器采样解码修复
- `CbtParser.decodePage()` 原先直接 `BitmapFactory.decodeByteArray(bytes)` 全尺寸解码，CBT 内大图会触发 OOM
- 修复后先读取图片尺寸并计算 `inSampleSize`，再按屏幕长边 1920px 采样解码，与 CBZ/CBR/CB7 保持一致

**相关文件：**
- CbtParser.kt

### 13.9 内存与性能优化修复 (2026-07-22)

#### 封面提取统一降采样
- `CoverExtractor` 所有格式（CBZ、CBR、CB7、CBT、EPUB 封面与 fallback、本地图片）原先直接全分辨率解码封面，大图易导致 OOM
- 新增 `decodeSampled()` 统一先读取边界尺寸并计算 `inSampleSize`，再按 `THUMB_MAX_SIZE=480px` 采样解码
- 保持封面文件缓存格式与清理逻辑不变

**相关文件：**
- CoverExtractor.kt

#### 分镜检测 Bitmap 生命周期兜底
- `PanelDetector.detectPanels()` 缩放后的 `scaled` Bitmap 仅在正常返回路径中被回收
- 若 `generatePanels()` / `sortPanels()` 抛出异常，`scaled` 可能泄漏
- 修复后用 `try/finally` 包裹中间处理逻辑，确保异常路径也回收

**相关文件：**
- PanelDetector.kt

#### 阅读器缩略图 Bitmap 泄漏修复
- 书签列表与底部缩略图条 (`ThumbnailItem`) 加载的 Bitmap 仅在组合中持有，未在离开组合时回收
- 修复后为每个缩略图项添加 `DisposableEffect`，在组件销毁时调用 `Bitmap.recycle()`
- 避免大量打开书签/缩略图面板时内存持续上涨

**相关文件：**
- ReaderScreen.kt

#### TTS 生命周期清理强化
- `TtsManager.shutdown()` 原先未移除 `UtteranceProgressListener`，且 `tts` 实例仍被持有
- 修复后先 `setOnUtteranceProgressListener(null)` 再 `shutdown()`，并置空 `tts` 引用
- 降低阅读器退出后 TTS 引擎泄漏风险

**相关文件：**
- TtsManager.kt

#### 页面 Bitmap 复制 OOM 降级
- `PageImage` 与放大镜原先强制复制为 `ARGB_8888`，对 RGB_565 源会额外翻倍内存，且 OOM 时直接回退到缓存引用有绘制崩溃风险
- 修复后优先按原配置复制；若 OOM 再尝试 `RGB_565`；仍失败才回退到缓存引用
- 在保证 UI 独立引用的同时降低内存峰值

**相关文件：**
- ReaderScreen.kt

#### 切换漫画磁盘缓存策略优化
- `PageCacheManager.setCurrentPage()` 在切换漫画时调用 `clearDiskInternal()` 清空全部磁盘缓存，导致其他漫画的跨会话缓存丢失
- 修复后改为仅 `clearByCacheId(oldCacheId)`，保留其他漫画的页面/缩略图磁盘缓存
- 提升多漫画交替阅读时的二次打开速度

**相关文件：**
- PageCacheManager.kt

#### DiskLruCache 写入并发优化
- `DiskLruCache.put()` 原先在持有写锁期间执行 `writer(tmp)`，大文件写入会长时间阻塞所有读取
- 修复后先在线程局部临时文件写入完整数据，再获取写锁执行原子重命名
- 读写并发性能提升，翻页时写入磁盘缓存不再卡顿

**相关文件：**
- DiskLruCache.kt

### 13.10 滤镜、统计与后台同步稳定性修复 (2026-07-22)

#### 滤镜链 Bitmap 生命周期修复
- `ImageUtils.applyAdjustments()` 链路中，`tintBitmap()` / `sharpenBitmap()` / `fastBlur()` 会在成功路径中 `recycle()` 输入 Bitmap
- 输入 Bitmap 可能来自页面缓存或 Coil，被回收后若缓存/Compose 仍持有引用，会导致绘制异常或崩溃
- 修复后：
  - 新增 `createBitmapSafe()`，OOM 时自动降级到 `RGB_565` 配置
  - 滤镜工具函数不再主动回收输入 Bitmap
  - `applyAdjustments()` 统一管理中间 Bitmap：产生下一步输出后仅回收自己生成的中间结果，绝不回收原始 `src`
- 同时 `applyFilters()` 也改用 `createBitmapSafe()`，大滤镜图 OOM 时安全回退到原图

**相关文件：**
- ImageUtils.kt

#### 阅读统计日级数据裁剪
- `SettingsRepository.updateReadingStats()` 的 `dailyMinutes` / `dailyPages` / `dailyReadDates` 会无限增长，长期积累后 JSON 体积膨胀、解析变慢
- 修复后每次更新时裁剪超过 730 天（约 2 年）的旧数据，保持统计存储 Bounded

**相关文件：**
- SettingsRepository.kt

#### 退出阅读器保存任务超时兜底
- `ReaderViewModel.releaseCache()` 与 `onCleared()` 使用 `applicationScope + NonCancellable` 确保进度/书签/阅读时长保存完成
- `NonCancellable` 会忽略取消信号，极端情况下若 DataStore 阻塞，协程可能长期挂起
- 修复后改用 `applicationScope` 并在每个保存步骤外包 `withTimeoutOrNull(30s)`：
  - 仍由应用级作用域保证 ViewModel 销毁后保存不丢失
  - 超时自动结束，防止阻塞后续启动或占用资源

**相关文件：**
- ReaderViewModel.kt

#### WebDAV 后台同步超时
- `WebDavSyncWorker.doWork()` 未设置整体超时，网络/DataStore 阻塞时 Worker 可能长期运行
- 修复后用 `withTimeout(5 分钟)` 包裹同步流程，超时或取消返回 `Result.failure()`，避免系统反复调度

**相关文件：**
- WebDavSyncWorker.kt

#### Application 初始化异常兜底
- `MangaReaderApplication.onCreate()` 中后台初始化任务（缓存清理、阅读会话恢复）若抛异常，可能导致应用启动崩溃
- 修复后为 `applicationScope` 配置 `CoroutineExceptionHandler`，并对启动任务加 `runCatching` 捕获日志

**相关文件：**
- MangaReaderApplication.kt

### 13.11 分享与备份安全加固 (2026-07-22)

#### FileProvider 路径范围收紧
- `file_paths.xml` 原先配置 `<cache-path name="cache" path="." />` 与 `<files-path name="files" path="." />`，通过 FileProvider 暴露整个应用缓存/文件目录，存在路径遍历与信息泄露风险
- 修复后仅暴露三个具体子目录：
  - `source_share/`：漫画源文件分享临时副本
  - `share_cards/`：阅读页分享卡片 PNG
  - `crash_logs/`：崩溃日志导出临时文件
- 其他缓存、DataStore、解析器临时文件不再可通过 FileProvider 被外部应用读取

**相关文件：**
- file_paths.xml
- AndroidManifest.xml

#### 分享临时文件统一子目录管理
- `ReaderViewModel.createShareCard()` 原先将 PNG 直接写入 `cacheDir` 根目录，与缓存文件混合
- `CrashLogDialog` / `SettingsScreen` 导出崩溃日志同样直接写入 `cacheDir` 根目录
- 修复后统一写入独立子目录：`share_cards/`、`crash_logs/`，便于清理与 FileProvider 最小化授权
- `ReaderViewModel.shareSourceFile()` 对 `file://` 源文件也统一复制到 `source_share/`，避免外部存储路径无法被 FileProvider 共享导致分享失败

**相关文件：**
- ReaderViewModel.kt
- CrashLogDialog.kt
- SettingsScreen.kt

#### 缓存管理页统计与清理同步更新
- `CacheManagementScreen` 的分享卡片统计与清理逻辑仍按旧路径扫描 `cacheDir` 根目录下的 `share_card_*.png`
- 修复后统一按 `share_cards/` 目录计算大小与删除
- 清理按钮改为 `deleteRecursively()`，子目录意外生成时也能清理

**相关文件：**
- CacheManagementScreen.kt
- CacheCleaner.kt

#### 本地备份敏感信息检查
- 审查 `SettingsRepository.exportAllData()` 导出的 `BackupData`：包含阅读进度、书签、收藏、分类、书单、阅读统计、阅读历史、漫画评分标签与阅读设置
- WebDAV 账号密码存储在独立加密 SharedPreferences，不在 `BackupData` 导出范围内，因此本地 JSON 备份不会泄露 WebDAV 密码
- 后续若扩展备份范围包含账号类配置，必须在导出前脱敏或加密敏感字段

**相关文件：**
- SettingsRepository.kt

### 13.12 解析与缓存层稳定性加固 (2026-07-22)

#### 解析器采样解码捕获 OOM
- CBZ/CBR/CB7/CBT/EPUB 的 `decodeSampled()` 原先只 `catch (e: Exception)`，遇到超大图片触发 `OutOfMemoryError` 时会直接向上抛出，导致阅读器崩溃
- 修复后增加 `catch (e: OutOfMemoryError)` 分支，记录日志并返回 `null`，让上层走降级或错误提示路径
- 同时保留 `CancellationException` 透传，避免协程取消被误吞

**相关文件：**
- parser/CbzParser.kt
- parser/CbrParser.kt
- parser/Cb7Parser.kt
- parser/CbtParser.kt
- parser/EpubParser.kt

#### 封面提取捕获 OOM
- [CoverExtractor.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/utils/CoverExtractor.kt) 的 `decodeSampled()` 同样只捕获 `Exception`，书架展示大量漫画封面时若遇大图 OOM 会崩溃
- 修复后捕获 `OutOfMemoryError` 并返回 `null`，书架封面加载失败处已能显示占位图

#### 磁盘缓存写入透传取消异常
- [DiskLruCache.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/cache/DiskLruCache.kt) 的 `put()` 在 writer 执行失败时统一 `catch (e: Exception)`，会吞掉 `CancellationException`
- 修复后先捕获 `CancellationException` 清理临时文件并重新抛出，保证协程取消语义正确

#### 页面缓存磁盘 I/O 透传取消异常
- [PageCacheManager.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/cache/PageCacheManager.kt) 的 `loadBytesFromDisk()`、`saveBytesToDisk()`、`saveBitmapToDisk()` 原先统一捕获 `Exception`
- 修复后分离 `CancellationException`，避免页面切换或退出阅读时取消信号被吞，减少不必要的后台 I/O

### 13.13 分享/崩溃临时目录清理与缓存管理完善 (2026-07-22)

#### source_share 与 crash_logs 自动清理
- 分享源文件副本与崩溃日志导出文件写入 `cacheDir/source_share` 和 `cacheDir/crash_logs` 后，若用户未手动清理可能长期占用空间
- [CacheCleaner.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/cache/CacheCleaner.kt) 启动时新增对这两个目录的清理，仅删除超过 24 小时的旧文件，避免误删正在进行的分享/导出
- 文档注释同步更新清理范围

#### 缓存管理页纳入 source_share / crash_logs
- [CacheManagementScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/settings/CacheManagementScreen.kt) 的「临时文件」统计原先只包含根目录下解析器临时文件
- 修复后 `source_share` 与 `crash_logs` 目录也计入临时文件大小，并且点击「清理临时文件」时会递归删除这两个目录

#### 注释修正
- [ReaderViewModel.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/reader/ReaderViewModel.kt) 的 `shareSourceFile()` 注释仍描述旧行为（file:// 直接返回），已更新为「所有来源统一复制到 source_share」

### 13.14 分享安全、缓存生命周期与备份版本校验 (2026-07-22)

#### 分享源文件目录遍历防护
- [ReaderViewModel.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/reader/ReaderViewModel.kt) 的 `copyToShareDir()` 直接使用 `content://` URI 的 `lastPathSegment` 作为文件名，若该段包含 `/` 或 `..` 可能写到 `source_share` 之外，存在目录遍历风险
- 新增 `sanitizeShareFileName()` 净化文件名：替换正反斜杠、去除空字符与首尾空白，空结果时回退为 `comic`，确保分享副本始终写入受控目录

#### 缓存管理「清除全部」完整性
- [CacheManagementScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/settings/CacheManagementScreen.kt) 的「清除全部」按钮原先只清理解析器临时文件前缀，遗漏 `share_cards/`、`source_share/`、`crash_logs/` 三个目录
- 修复后将这三个目录加入清除列表，与「临时文件」单项的清理范围保持一致，避免用户点击「清除全部」后仍有残留

#### 磁盘缓存 clear 增强
- [DiskLruCache.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/cache/DiskLruCache.kt) 的 `clear()` 使用 `File.delete()`，若目录非空会清理失败
- 改为 `deleteRecursively()` 并包裹 `runCatching`，确保异常情况也能继续清理其他文件

#### 缓存后台协程/线程生命周期
- [PageCacheManager.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/cache/PageCacheManager.kt) 持有独立的 `cacheScope` 与两个 `DiskLruCache` 的 `trimExecutor`，此前没有显式关闭入口，理论上进程结束时随 JVM 回收，但缺少兜底
- 新增 `PageCacheManager.shutdown()`：取消预读任务、关闭 `cacheScope`、关闭页面/缩略图磁盘缓存的 trim 线程
- [MangaReaderApplication.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/MangaReaderApplication.kt) 新增 `onTerminate()` 调用 `PageCacheManager.shutdown()`，作为应用正常退出时的兜底释放

#### 本地备份版本校验与迁移桩子
- [SettingsRepository.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/data/SettingsRepository.kt) 的 `importAllData()` 原先只警告版本高于 1 的备份，未拒绝过低版本，也没有迁移入口
- 新增 `BACKUP_VERSION` 与 `MIN_SUPPORTED_BACKUP_VERSION` 常量，导出时显式写入当前版本
- 导入时：低于最低支持的版本直接拒绝并返回 false；高于当前版本的备份保留警告但尝试导入；通过 `migrateBackupIfNeeded()` 预留未来字段迁移钩子

#### ReaderStatusBar 硬编码颜色消除
- [ReaderScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/reader/ReaderScreen.kt) 的 `ReaderStatusBar` 背景使用硬编码 `Color(0x66000000)`
- 改为 `MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f)`，使状态栏背景随主题变化，保持暗色主题下的半透明遮罩效果

### 13.15 书架状态持久化、WebDAV 认证失败终止与崩溃日志脱敏 (2026-07-22)

#### 书架 UI 状态持久化
- [BookshelfScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/bookshelf/BookshelfScreen.kt) 的 `viewMode`、`filter`、`sortBy`、`selectedCategory`、`searchQuery` 原先使用 `remember`，在配置变更或进程重启后会重置
- 统一替换为 `rememberSaveable`，并为 `BookshelfFilter`、`BookshelfSort`、`BookshelfViewMode` 三个私有枚举编写 `Saver`，实现字符串 name 与枚举值的互转
- 书架视图模式、筛选条件、排序方式、当前选中的分类与搜索关键词现在可在配置变更和短时间后台回收后保持

#### WebDAV 认证失败不再无限重试
- [WebDavSync.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/utils/WebDavSync.kt) 的上传、下载与连接测试在收到 HTTP 401/403 时返回带「认证失败」前缀的异常
- [WebDavSyncWorker.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/utils/WebDavSyncWorker.kt) 新增 `isAuthFailure()`，识别到认证错误后返回 `Result.failure()`，避免 Worker 因密码错误无限指数退避重试

#### 崩溃日志脱敏
- [CrashHandler.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/utils/CrashHandler.kt) 记录的堆栈中可能包含 `/data/data/com.mangareader/...` 等应用私有路径
- `init()` 阶段收集 `dataDir`、`filesDir`、`cacheDir`、`externalFilesDir`、`externalCacheDir`、`sourceDir`、`logDir` 等敏感路径，按长度降序排列
- `logCrash()` 写入文件前调用 `sanitize()`，将上述路径统一替换为 `<private>` 占位符，降低日志分享时的隐私泄露风险

### 13.16 通知大图标与崩溃日志大小限制 (2026-07-22)

#### 阅读目标通知大图标
- [NotificationUtils.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/utils/NotificationUtils.kt) 的阅读目标达成通知原先只有小图标，在通知栏中辨识度较低
- 新增 `createLargeIcon()`，使用应用启动前景图标生成 128x128 的位图大图标，提升通知视觉识别度
- 大图标获取失败时静默回退，不影响通知发送

#### 崩溃日志单文件大小限制
- [CrashHandler.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/utils/CrashHandler.kt) 仅限制日志文件数量为 5 个，未限制单个文件大小
- 新增 `MAX_LOG_SIZE_BYTES = 256KB`，写入前检查脱敏后日志的字节长度，超过阈值时从尾部截断并附加提示，防止极端堆栈导致存储被占满

### 13.17 文件浏览器空状态与错误提示 (2026-07-22)

#### 问题
- [BrowserScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/browser/BrowserScreen.kt) 在目录加载完成但条目为空、或加载出错时，只显示空白 `LazyColumn`，用户无法区分「加载中」「空目录」「加载失败」三种状态
- [BrowserViewModel.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/browser/BrowserViewModel.kt) 的 `UiState` 虽然已有 `isLoading` 和 `error` 字段，但 UI 层未消费

#### 修复
- 在 `BrowserScreen` 的 `Box` 中按优先级展示：权限拒绝说明、加载指示器、错误状态（含重试按钮）、空目录提示、文件列表
- 新增 `EmptyState()` 与 `ErrorState()` 两个私有 Composable，保持与书架空状态一致的视觉风格
- 添加字符串资源 `browser_empty_title`、`browser_empty_subtitle`、`browser_error_retry`

### 13.18 主题一致性：消除书架与阅读器弹窗中的硬编码颜色 (2026-07-22)

#### 问题
- [BookshelfScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/bookshelf/BookshelfScreen.kt) 的阅读状态角标使用硬编码 `#4CAF50`（已读）和 `#FF9800`（阅读中），切换主题后仍保持固定颜色
- [ReaderScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/reader/ReaderScreen.kt) 的书签空状态、章节列表空状态、当前章节提示使用硬编码 `Color.Gray`，在浅色/深色主题下对比度不一致

#### 修复
- 书架状态角标改为 `MaterialTheme.colorScheme.primary` / `secondary`，未读保持透明，文字仍使用白色保证可读性
- 阅读器弹窗中的提示文本统一改为 `MaterialTheme.colorScheme.onSurfaceVariant`，跟随主题自动适配
- [BookshelfScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/bookshelf/BookshelfScreen.kt) 添加缺失的 `androidx.compose.ui.graphics.Color` import，替换剩余全限定名引用

### 13.19 统计图表条形图最大值动态计算 (2026-07-22)

#### 问题
- [StatisticsScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/stats/StatisticsScreen.kt) 的 `TimeBar` 使用硬编码 `max = 120` 分钟作为条形图相对长度基准
- 当某天/月阅读时长超过 120 分钟后，所有长条都被截断到 100%，无法直观比较真实差异

#### 修复
- `TimeBar` 新增 `max: Int` 参数，由调用方根据实际数据集动态计算
- 日统计使用近 14 天数据的最大值，`coerceAtLeast(1)` 避免除零
- 月统计使用近 12 个月数据的最大值，同样做空数据保护

### 13.20 阅读器设置保存、翻页进度防抖与放大镜内存安全 (2026-07-22)

#### 问题
- [ReaderViewModel.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/reader/ReaderViewModel.kt) 的 `updateSettings` 自行管理 300ms 延迟保存 Job，连续快速切换设置时旧 Job 被取消；虽然 `_uiState` 已合并最新状态，但本地存在与 `SettingsRepository.saveAsync` 重复且生命周期更短的防抖逻辑
- [ReaderViewModel.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/reader/ReaderViewModel.kt) 的 `setCurrentPage` 每翻一页都立即写入 DataStore（进度、总页数、阅读统计、目标检测），快速翻页时产生高频 IO，增加耗电与 ANR 风险
- [ReaderScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/reader/ReaderScreen.kt) 的 `PageViewer` 在 Composable 销毁时已回收放大镜位图，但未区分「独立副本」与「OOM 回退时引用的缓存位图」，理论上可能误回收缓存中的 `rawBmp`
- [GestureSettingsScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/settings/GestureSettingsScreen.kt) 点击保存后立即 `onBack()`，DataStore 写入在协程中异步执行，用户迅速杀进程可能导致手势配置丢失
- [MangaReaderTheme.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/ui/theme/MangaReaderTheme.kt) 的 `ColorScheme` 仅设置少量颜色槽，`error`、`outline`、`scrim`、`surfaceTint` 等使用 Material3 默认值，与自定义深色主题不协调；[ReaderScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/reader/ReaderScreen.kt) 的状态栏背景直接依赖 `colorScheme.scrim`

#### 修复
- `updateSettings` 移除本地 `settingsJob`，统一使用 `SettingsRepository.saveAsync` 的 Repository 级防抖保存；删除 `settingsJob` 字段与相关取消逻辑，避免重复 Job
- `setCurrentPage` 对进度/统计/目标检测写入增加 `PROGRESS_SAVE_DEBOUNCE_MS = 400ms` 防抖；新增 `flushProgressAndStats()` 统一写入逻辑，`onCleared()` 退出兜底时复用该方法，防止快速翻页后立即退出导致统计丢失
- `PageViewer` 新增 `magnifierBitmapOwned` 状态：位图复制成功时标记为 owned，OOM 回退到 `rawBmp` 时标记为未拥有；`DisposableEffect` 与 `onDismiss` 仅回收 owned 副本，避免误回收缓存位图
- `GestureSettingsScreen` 将保存与返回放入同一协程：`scope.launch { repo.saveReaderSettings(...); onBack() }`，确保持久化完成后再退出
- `MangaReaderTheme` 补全 `error`、`onError`、`outline`、`outlineVariant`、`scrim`、`surfaceTint`、`inverseSurface`、`inverseOnSurface`、`inversePrimary` 等颜色槽，深/浅色主题保持一致风格

### 13.21 书架/浏览器/同步/缓存管理细节修复 (2026-07-22)

#### 问题
- [BookshelfViewModel.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/bookshelf/BookshelfViewModel.kt) 的 `renameComic` 在 `try` 块内使用 `return@launch callback(false)` 提前退出协程，随后 `finally` 块再次调用 `callback(success)`，导致 UI 收到两次回调
- [BrowserScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/browser/BrowserScreen.kt) 调用 `takePersistableUriPermission` 时未处理 `SecurityException`，也未校验持久权限是否真正被授予；某些 ROM 下目录选择后应用重启无法访问
- [WebDavSyncWorker.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/utils/WebDavSyncWorker.kt) 的 `doWork` 捕获 `CancellationException` 后返回 `Result.failure()`，WorkManager 会将其视为失败并可能触发不必要重试
- [CacheManagementScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/settings/CacheManagementScreen.kt) 与 [CacheCleaner.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/cache/CacheCleaner.kt) 对解析器临时文件的前缀列表不一致：统计界面过滤 `tmp_`，清理逻辑只清理 `temp_`，导致部分临时文件显示为可清理但启动时不会被清理
- [FileManager.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/utils/FileManager.kt) 的 `delete` 对 `content://` URI 调用 `doc.delete()` 后丢弃返回值，调用方无法判断删除是否成功

#### 修复
- `renameComic` 将 `return@launch callback(false)` 改为 `return@launch`，由 `finally` 统一回调一次
- `BrowserScreen` 用 `runCatching` 包裹 `takePersistableUriPermission`，并通过 `contentResolver.persistedUriPermissions` 验证读写权限是否被授予；未成功时通过 `SnackbarHost` 提示用户重新选择
- `WebDavSyncWorker` 捕获 `CancellationException` 后直接 `throw e`，让 WorkManager 正确标记为取消
- 统一 `CacheCleaner` 与 `CacheManagementScreen` 的临时文件前缀列表，新增 `tmp_`、`temp_`、`cbr_temp_`、`cbt_temp_`、`cbz_`、`cbr_`、`cbt_`、`cb7_` 等常见解析器遗留文件/目录前缀
- `FileManager.delete` 对 `content://` 返回 `doc.delete()` 的实际结果

### 13.22 解析器/图像解码/Bitmap 复用优化 (2026-07-22)

#### 问题
- `CbzParser`/`CbrParser`/`CbtParser`/`EpubParser` 使用固定 `DEFAULT_MAX_DIMENSION = 1920` 作为采样目标，未按真实屏幕分辨率降采样，在高分屏上画质不足、在低端机上又可能 OOM
- 上述解析器以及 `Cb7Parser` 的 `calculateInSampleSize` 算法过于保守：仅当「采样后宽高仍都 ≥ 目标值」时才继续加倍，导致很多大图仍按原尺寸解码，失去采样意义
- 所有解析器、`ParserBasedComicProvider.getThumbnail`、`CoverExtractor` 的缩略图解码均未使用 `BitmapFactory.Options.inBitmap`，每次解码都重新分配 Bitmap，GC/内存抖动大，违反项目硬约束
- `PageCacheManager` 内部维护私有 `BitmapPool`，解析器无法复用，存在重复实现
- `CacheCleaner` 与 `CacheManagementScreen` 不清理 `CbtParser` 生成的 `cbt_index_*.json` 索引文件，旧索引会永久堆积
- `CbtParser` 使用 `archive.absolutePath.hashCode()` 作为索引文件名 key，32 位哈希存在冲突风险，可能导致不同漫画共用错误索引

#### 修复
- 新增公共 [BitmapPool.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/utils/BitmapPool.kt)，将原 `PageCacheManager` 私有 Bitmap 池提取为全应用共享对象，供缓存层、解析器、封面提取统一使用
- 新增 [ParserBitmapUtils.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/parser/ParserBitmapUtils.kt)，集中实现：
  - `getScreenSize()` 获取屏幕真实分辨率
  - `calculateInSampleSize()` 以「长边不超过屏幕长边」为核心逻辑，确保大图一定被降采样
  - `decodeSampled()` 自动从 `BitmapPool` 取 `inBitmap`、解码失败/未使用时归还，减少 Bitmap 分配
- `CbzParser`/`CbrParser`/`Cb7Parser`/`CbtParser`/`EpubParser` 全部改用 `ParserBitmapUtils.decodeSampled()`，移除各自的 `DEFAULT_MAX_DIMENSION`、`decodeSampled()`、`calculateInSampleSize()` 重复代码
- `ParserBasedComicProvider.getThumbnail()` 与 `CoverExtractor.decodeSampled()` 改用 `ParserBitmapUtils.decodeSampled()`，缩略图与封面提取也接入 Bitmap 复用池
- `PageCacheManager` 删除私有 `BitmapPool`，改为导入公共 `com.mangareader.utils.BitmapPool`
- `CacheCleaner.tempFilePrefixes` 与 `CacheManagementScreen` 统计过滤列表增加 `cbt_index_`
- `CbtParser` 增加 `safeIndexKey()`，使用 `absolutePath.hashCode() + 路径后缀 hashCode` 组合生成索引文件名，显著降低冲突概率

### 13.23 无障碍、返回键稳定性与外部文件扩展名兼容 (2026-07-22)

#### 阅读器页面可访问性描述
- [ReaderScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/reader/ReaderScreen.kt) 的 `PageImage` 原先仅描述为「第 X 页」，屏幕阅读器用户无法获知总页数
- 新增 `totalPages` 参数贯穿 `PageViewer`、`DualPageLayout`、`VerticalScrollViewer`、`HorizontalScrollViewer`，使用 `page_content_description` 输出「第 X 页，共 Y 页」
- 提升 TalkBack 等辅助工具的阅读体验

#### 返回键回调稳定性
- `BackHandler` 的回调使用 `remember(panelState.isActive, showMenu, viewModel, onBack)` 作为 key，`viewModel` 与 `onBack` 会导致重组时频繁重新生成 lambda
- 改为使用 `rememberUpdatedState` 捕获最新状态，并使用无 key 的 `remember` 保持回调实例稳定，减少不必要的重组

#### 外部文件扩展名大小写兼容
- [AndroidManifest.xml](file:///d:/manhua2222/app/src/main/AndroidManifest.xml) 的 `pathPattern` 仅声明小写扩展名（`.cbz`、`.cbr` 等），从文件管理器打开 `.CBZ`、`.PDF` 等大写扩展名文件时无法匹配
- 补充大写扩展名 `pathPattern`，覆盖 `.CBZ/.CBR/.CB7/.CBT/.EPUB/.PDF/.ZIP`

### 13.24 日漫模式双页左右顺序修复 (2026-07-22)

#### 问题
- 日漫（RTL）双页模式下，[ReaderScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/reader/ReaderScreen.kt) 的 `computeDisplayIndices` 返回逻辑索引 `[当前页, 下一页]` 后，如果在此处再做 `indices.reversed()`，会导致 `DualPageLayout` 的 RTL 分支再次交换，最终左右页顺序错误

#### 修复
- `computeDisplayIndices` 统一返回 `[当前页, 下一页]` 的逻辑顺序，不再因 RTL 反转
- `DualPageLayout` 单独负责视觉排布：RTL 时 `leftIndex = indices[1]`、`rightIndex = indices[0]`，非 RTL 时保持 `leftIndex = indices[0]`、`rightIndex = indices[1]`
- 这样日漫双页阅读的视觉顺序为右页在前、左页在后，与国漫/左翻页模式互不干扰

---

### 13.25 阅读器、缓存、解析器与同步稳定性修复 (2026-07-22)

#### 阅读器/UI 层
- 修复 [ReaderScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/reader/ReaderScreen.kt) 中 `brightnessOverlay` 在 `settings.brightness` 为 0 时的除零异常，避免亮度叠加渲染异常
- 缩略图预览（书签、页面预览、底部滑块）现在从缓存获取的位图会复制独立副本，解决 UI 回收缩略图时污染 `PageCacheManager` 内存缓存的问题

#### ViewModel/缓存层
- [ReaderViewModel.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/reader/ReaderViewModel.kt) 的 `processedBitmapCache.sizeOf` 改用 `allocationByteCount`，并跳过已回收位图，提升缓存大小计算准确性
- [PageCacheManager.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/cache/PageCacheManager.kt) 与 [DiskLruCache.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/cache/DiskLruCache.kt) 的 `shutdown()` 增加幂等保护，防止重复调用导致 `IllegalStateException`

#### 解析器层
- [CbtParser.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/parser/CbtParser.kt) 将 `RandomAccessFile` 的 seek/read 完整操作放入 `stateLock` 同步块，避免 `close()` 与翻页读取竞态
- [EpubParser.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/parser/EpubParser.kt) 将全局互斥锁替换为 `ReentrantReadWriteLock`：解析持写锁，单页读取持读锁，允许并发翻页而不阻塞

#### 设置/同步层
- [GestureSettingsScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/settings/GestureSettingsScreen.kt) 保存协程中单独捕获 `CancellationException` 并重新抛出，避免取消时被通用 `Exception` 分支恢复 `saving` 状态
- [WebDavSyncWorker.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/utils/WebDavSyncWorker.kt) 的调度器对 `WorkManager` 操作增加 try-catch，防止未初始化等异常导致崩溃

---

### 13.26 构建验证与收尾确认 (2026-07-22)

- 执行 `./gradlew assembleDebug` 构建成功，耗时约 0.8s，无 Kotlin 编译警告
- 13.24 与 13.25 中涉及的所有修复（日漫双页顺序、亮度除零、缓存 shutdown 幂等、EPUB/CBT 解析器并发安全、GestureSettingsScreen 保存状态、WebDAV Worker 异常捕获等）均已通过编译验证
- 当前代码基线可进入测试或下一轮的审查/增强

---

### 13.27 阅读器稳定性、书架性能与系统整合修复 (2026-07-22)

#### 阅读器/TTS
- [TtsManager.kt] 支持按 `utteranceId` 回传完成回调；[ReaderViewModel.kt] 新增 `currentTtsUtteranceId` 过滤过期回调，避免快速翻页/取消后的 TTS 竞态
- [TtsManager.kt] 暴露 `initSuccess` 状态流，[ReaderScreen.kt] 在 TTS 开启但初始化失败时提示用户检查系统语音设置
- [ReaderViewModel.kt] 自动翻页增加 `autoPageWasRunning` 标志，菜单隐藏后仅在之前正在自动翻页时才恢复，避免临时呼出菜单后意外恢复
- [ReaderViewModel.kt] 自动翻页跨章节跳转后额外等待一倍间隔，让用户看清新章节封面/标题页

#### 书架/浏览器/UI
- [BookshelfViewModel.kt] 新增 `SettingsState`，将 favorites/categories/comicCategories/bookmarksMap/stats/extrasMap/readingLists/readerSettings/bookshelfUris/currentBookshelfIndex 等 10+ 个 DataStore Flow 合并为单一 Flow
- [BookshelfScreen.kt] 改为收集聚合后的 `settingsState`，减少大图书馆下的频繁重组；搜索防抖由 250ms 延长至 500ms
- [BookshelfScreen.kt] 为 4 处 `AsyncImage` 统一设置透明占位/错误画笔，确保封面加载失败时仍显示默认图标而非空白
- [BrowserViewModel.kt] 重写 `listStorageRoots()`：Android 11+ 优先使用 `StorageManager.storageVolumes`，低版本回退到遍历文件树推导根目录，避免硬编码 `/Android/data/` 路径在部分设备上失效

#### 文件/缓存/同步
- [FileListHelper.kt] 空目录结果也加入缓存，避免反复进入空 SAF 目录时重复发起 `DocumentFile.listFiles()`
- [CoverExtractor.kt] 新增封面缩略图数量上限（200 张），超过时按修改时间删除最旧文件，避免缓存无限堆积
- [WebDavSync.kt] `downloadSyncData()` 增加 10MB 上限，防止恶意/异常大文件导致 OOM
- [WebDavSync.kt] `testConnection()` 除了 OPTIONS 探测外，增加 PUT + GET 临时文件的实际读写验证
- [SettingsScreen.kt] 恢复备份前检查文件大小（上限 50MB），过大时提示用户
- [CrashHandler.kt] `readCrashLogs()` / `consumeCrashLogs()` 增加总读取上限 512KB，防止长期累积日志导致 OOM

#### 系统整合
- [MangaReaderApp.kt] 外部 Intent 启动的 `startUri` 增加 `rememberSaveable` 消费标记，避免配置变更或进程恢复后重复导航
- [MainActivity.kt] Android 13+ 通知权限请求仅首次启动时弹出一次，避免用户拒绝后每次启动都重复请求
- [BrightnessSensorManager.kt] 光线传感器采样由 `SENSOR_DELAY_UI` 改为 `SENSOR_DELAY_NORMAL`，并增加低通滤波与阈值过滤，降低环境光抖动导致的频繁重组

---

### 13.28 阅读器并发安全与缓存加载竞态修复 (2026-07-22)

#### 阅读器/ViewModel 并发安全
- [ReaderViewModel.kt] `loadComic()` 中先保存旧 `loadJob` / `parserRef` / `provider` 引用，取消旧任务后在新协程内 `cancelAndJoin()` 并关闭旧资源，避免旧加载任务访问已关闭的解析器/提供者导致崩溃
- [ReaderViewModel.kt] `ttsEmptySkipCount` 与 `currentTtsUtteranceId` 改为 `AtomicInteger` / `AtomicReference`，防止 TTS 引擎回调线程与 `speakCurrentPage` 协程并发读写
- [ReaderViewModel.kt] `onCleared()` 中退出保存改为直接调用 `saveProgress` / `saveTotalPages`，不再重复调用 `flushProgressAndStats` 累计阅读统计，避免翻页防抖任务与退出兜底保存重复统计

#### 缓存层并发安全
- [PageCacheManager.kt] `loadLocks` 由 `ConcurrentHashMap<Int, Any>` 改为 `ConcurrentHashMap<Int, Mutex>`，`loadIntoMemory()` 使用 `Mutex.withLock` 将同一 index 的缓存加载串行化，避免旧双重检查锁在挂起函数 `getPage` 执行期间失效导致同一页被重复解码

#### UI/系统整合
- [ReaderScreen.kt] 卷末检测 `LaunchedEffect` 增加 `state.currentPage >= state.totalPages - 1` 条件，仅在到达最后一页时才调用 `checkChapterEnd()`，减少普通翻页时不必要的 ViewModel 访问与弹窗判断
- [MainActivity.kt] `onDestroy()` 仅在 `isFinishing` 为 true 时才调用 `ReaderKeyEvents.clear()`，避免配置变化导致的 Activity 重建清空当前有效的音量键处理器

#### 设置层并发安全
- [SettingsRepository.kt] 为 `saveJob` 增加 `synchronized` 锁保护，防止多线程同时调用 `saveAsync()` 时产生多个并发 DataStore 写入任务，确保最终只保留最新一次完整设置写入

### 13.29 安全、兼容性与工程化加固 (2026-07-22)

#### 外部 Intent 与 URI 安全
- [MainActivity.kt] 外部启动 URI 改为通过 `onSaveInstanceState` / `onRestoreInstanceState` 持久化，进程被系统回收后仍可继续外部打开流程
- [MainActivity.kt] 新增 `isValidExternalUri()` 校验，仅允许 `content://` 与带非空 path 的 `file://` URI，过滤非法 scheme
- [MangaReaderApp.kt] 外部 `content://` URI 在打开前先调用 `FileManager.resolveExternalUri()`：优先申请持久读/写权限，若文件管理器未授予则复制到 `files/external_imports/` 私有目录；超过 200MB 的大文件保留原 URI，避免占用过多用户存储

#### 文件路径安全
- [FileManager.kt] 新增 `toSafeFile()`，使用 `canonicalFile` 解析符号链接与 `..`，降低路径遍历风险
- [FileManager.kt] `rename()` / `delete()` / `move()` / `copy()` / `batchRename()` 的 `file://` 分支全部改用 `toSafeFile()` 获取规范化 File

#### 构建与发布
- [build.gradle.kts] 将占位版本 `versionCode = 1`、`versionName = "1.0"` 更新为 `versionCode = 29`、`versionName = "1.0.29"`，避免发布包版本冲突
- [proguard-rules.pro] 新增 parser 包 keep 规则：保留 `ComicParser` 接口、`ParserFactory` 及所有实现类，防止 R8 fullMode 收缩后解析器工厂失效

#### 测试覆盖
- 新增 `ParserFactoryTest`：覆盖 `detectType`、`isImageFile`、`getParser` 对 CBZ/CBR/CB7/CBT/EPUB/PDF/FOLDER/COMIC_BOOK/UNKNOWN 的判定
- 新增 `FileManagerTest`：覆盖 `toSafeFile` 对正常路径、路径遍历、空白路径的规范化行为
- `./gradlew testDebugUnitTest` 通过，13 个单元测试全部成功

---

### 13.30 UI 细节、缓存清理与传感器上下文修复 (2026-07-22)

#### 通知与系统整合
- [NotificationUtils.kt] 通知小图标由 `ic_launcher_foreground` 改为新增 [ic_notification.xml](file:///d:/manhua2222/app/src/main/res/drawable/ic_notification.xml)；新图标为 24dp 纯白透明背景，符合 Android 8+ 通知栏 Alpha 图标要求，避免显示为白色方块

#### 阅读器体验
- [BrightnessSensorManager.kt] 通过 `context.applicationContext` 获取 `SensorManager`，避免持有 Activity Context 导致潜在的上下文泄漏
- [ReaderScreen.kt] `screenWidthPx` / `screenHeightPx` 的 `remember` key 由 `density` / `configuration` 对象改为稳定的 `screenWidthDp` / `screenHeightDp` 整型值，减少 Density 实例变化带来的不必要重组

#### 分享缓存清理
- [ReaderViewModel.kt] `createShareCard()` 生成新 PNG 前先清理 `share_cards/` 目录下的旧卡片，避免同一会话多次分享导致缓存累积
- [CacheCleaner.kt] `cleanShareCards()` 改为按 `MAX_CACHE_AGE_MS`（24 小时）删除，避免启动时误删用户刚生成、尚未分享出去的卡片
- [ReaderViewModel.kt] `shareSourceFile()` 的 `file://` 分支改用 `FileManager.toSafeFile(uri)` 获取规范化 File，统一路径安全处理

---

### 13.31 Provider、阅读统计与崩溃日志隐私修复 (2026-07-22)

#### Provider 稳定性
- [ParserBasedComicProvider.kt] 缩略图 fallback 路径不再 `recycle()` 原始位图，避免 `PageCacheManager` 内存缓存中的位图被缩略图提取污染或回收，防止花屏/崩溃
- [ParserBasedComicProvider.kt] `close()` 增加 `AtomicBoolean` 幂等保护，避免重复调用导致底层解析器二次关闭

#### 阅读统计
- [SettingsRepository.kt] `endReadingSession()` 对单次会话时长增加 `MAX_READING_SESSION_MINUTES = 240`（4 小时）上限，避免应用被系统回收多日后再启动时一次性计入过长的不真实阅读时长
- [SettingsRepository.kt] 启动恢复会话逻辑 `recoverReadingSession()` 已存在的 240 分钟上限保持不变，两处上限统一

#### 崩溃日志隐私
- [CrashHandler.kt] 脱敏逻辑增加正则替换：`/storage/...` 与 `/sdcard/...` 替换为 `<external>`，`content://...` 与 `file://...` 替换为 `<content-uri>` / `<file-uri>`，进一步降低用户文件路径与 URI 在日志分享时泄露的风险

---

### 13.32 ProGuard 与 AndroidManifest 发布配置完善 (2026-07-22)

#### ProGuard / R8
- [proguard-rules.pro] 新增全局枚举 keep 规则，保留 `values()` 与 `valueOf(String)`，避免 `BookshelfScreen` 中通过 Saver 恢复的枚举在混淆构建下崩溃
- [proguard-rules.pro] 显式 keep `WebDavConfig`、`SyncData`、`ComicMetadata` 等顶层 `@Serializable` 类，确保 WebDAV 配置、同步数据、元数据在发布构建中可正常序列化/反序列化

#### AndroidManifest
- [AndroidManifest.xml] 外部打开 MIME 类型过滤增加 `application/vnd.comicbook+zip` 与 `application/vnd.comicbook-rar`/`application/x-cbr`，提升文件管理器兼容性
- [AndroidManifest.xml] 新增 `android:dataExtractionRules="@xml/data_extraction_rules"`，明确禁止 Android 12+ 云端备份与本地 D2D 设备传输，与 `allowBackup="false"` 保持一致
- 新增 [data_extraction_rules.xml](file:///d:/manhua2222/app/src/main/res/xml/data_extraction_rules.xml) 规则文件

---

### 13.33 书架 SAF 权限与备份保存健壮性修复 (2026-07-22)

#### 书架
- [BookshelfScreen.kt] 添加书架目录时同时申请 `FLAG_GRANT_READ_URI_PERMISSION` 与 `FLAG_GRANT_WRITE_URI_PERMISSION`，确保 SAF 书架后续可执行重命名、删除等写操作

#### 设置/备份
- [SettingsScreen.kt] 备份保存时检查 `contentResolver.openOutputStream(uri)` 是否为 null，若目标位置不可写则提示失败，避免误报“保存成功”

### 13.34 入口 URI 持久化、路径规范化与发布配置加固 (2026-07-23)

#### 入口 / 导航
- [MainActivity.kt] 启动 URI 已通过 `onSaveInstanceState` / `savedInstanceState` 实现进程死亡恢复，并校验 scheme 为 `content://` 或带非空路径的 `file://`
- [MangaReaderApp.kt] 导航回调使用 `remember(navController)` 包裹，降低重组时反复创建 lambda 带来的不必要重组
- [ReaderViewModel.kt] 写入阅读历史前调用 `FileManager.isDurableUri()` 校验 URI 可持续访问，防止临时 `content://` 分享 URI 因权限失效导致历史记录无法打开

#### 路径规范化
- [FileManager.kt] 新增 `isDurableUri()` 辅助方法，统一判断 URI 是否持久可读
- [BookshelfViewModel.kt] 删除、重命名、读取修改时间、创建 `.nomedia` 等 `file://` 操作统一改用 `FileManager.toSafeFile()` 规范化路径
- [CbtParser.kt] 解析本地 TAR 文件时同样使用 `FileManager.toSafeFile()`，消除符号链接/`..` 带来的索引键不一致

#### 发布配置
- [app/build.gradle.kts] `versionCode` 更新为 `30`，`versionName` 更新为 `1.0.30`
- [app/proguard-rules.pro] 补充 `-keep class com.mangareader.parser.** { *; }`，防止 R8 fullMode 剥离解析器内部辅助类

#### 测试
- 现有 `FileManagerTest` 与 `ParserFactoryTest` 已通过 `./gradlew testDebugUnitTest` 验证

### 13.35 传感器、SMB Range 与 EPUB 封面解析细节强化 (2026-07-23)

#### 解析器
- [CoverExtractor.kt] 消除 EPUB 二次遍历 OPF 时的 `!!` 强转，改为局部不可空引用，降低 NPE 风险

#### 传感器
- [BrightnessSensorManager.kt] `start()` 现在返回 `registerListener()` 的真实结果；没有传感器或注册失败时返回 false，避免状态与实际不一致

#### 网络/SMB
- [SmbClient.kt] `readRange()` 增加参数校验与 50MB 上限：拒绝负数范围、倒置范围及异常 Content-Length，防止恶意/错误响应导致 OOM

#### 阅读界面
- [ReaderScreen.kt] 顶部状态栏时间格式化器使用 `remember` 缓存，避免每分钟刷新时重复创建 `SimpleDateFormat`

### 13.36 阅读器分享健壮性与浏览器路径规范化 (2026-07-23)

#### 阅读器分享
- [ReaderScreen.kt] 分享源文件与分享卡片调用 `startActivity()` 时增加 `runCatching` 保护，避免设备上没有可用分享应用时直接崩溃；失败时通过 Toast 提示用户

#### 浏览器
- [BrowserViewModel.kt] 存储卷根目录与 `deriveStorageRoot()` 回退路径统一使用 `FileManager.toSafeFile()` 规范化，返回前再做 canonical 去重，避免符号链接/`..` 把同一目录识别成多个卷

---

### 13.37 日漫 RTL 手势与阅读进度写入优化 (2026-07-23)

#### 日漫模式左右页翻页方向
- [ReaderScreen.kt] 单页/双页模式下的水平滑动手势新增 RTL 分支：日漫从右向左阅读时，向右滑动翻到下一页、向左滑动返回上一页，与 LTR 方向相反，修复日漫模式下左右滑动翻页方向错误的问题

#### 阅读进度写入
- [ReaderViewModel.kt] 漫画初始加载时的整体进度、总页数、章节进度写入由三次独立 DataStore `edit` 合并为一次 `saveReadingProgress()` 事务，与翻页时的批量保存策略保持一致，降低初始加载 IO 开销

#### 代码清理
- [BrowserViewModel.kt] 移除 `detectDirectoryTypeRecursive()` 末尾冗余的 `if (hasImages) FOLDER else FOLDER` 分支，统一返回 `ComicType.FOLDER`，避免误导性死代码

#### 发布配置
- [app/build.gradle.kts] `versionCode` 更新为 `31`，`versionName` 更新为 `1.0.31`

#### 测试
- `./gradlew assembleDebug testDebugUnitTest` 通过，13 个单元测试全部成功

---

### 13.38 封面缓存与书架删除健壮性优化 (2026-07-23)

#### 封面加载性能
- [BookshelfScreen.kt] 为 4 处封面 [AsyncImage] 统一添加 `memoryCacheKey` 与 `diskCacheKey`，避免书架滚动/切换时重复解码相同封面，提升列表流畅度
- [HistoryScreen.kt] 为历史记录封面 [AsyncImage] 同样添加 `memoryCacheKey` 与 `diskCacheKey`

#### 书架删除
- [BookshelfViewModel.kt] `removeComics()` 中 `content://` 删除后校验 `DocumentFile.delete()` 返回值，失败时记录日志，便于排查 SAF 写权限失效或文件被占用导致的删除失败

#### 发布配置
- [app/build.gradle.kts] `versionCode` 更新为 `32`，`versionName` 更新为 `1.0.32`

#### 测试
- `./gradlew assembleDebug testDebugUnitTest` 通过，13 个单元测试全部成功

---

### 13.39 统计、日期与缓存生命周期加固 (2026-07-23)

#### 日期格式化线程安全
- 全项目移除 `SimpleDateFormat` / `Calendar` / `java.util.Date` 用法，统一替换为 `java.time.*` API（`LocalDate`、`LocalDateTime`、`DateTimeFormatter`），消除多线程/协程并发下的日期解析异常与状态竞争
- 涉及文件：`ComicExtras.kt`、`SettingsRepository.kt`、`StatisticsScreen.kt`、`ReaderScreen.kt`、`HistoryScreen.kt`、`BookshelfScreen.kt`、`CrashHandler.kt`、`MangaReaderTheme.kt`

#### 阅读目标检测一致性
- `SettingsRepository.kt` 新增 `getGoalsAndStats()`，在单个 DataStore 读取事务中同时返回 `ReadingGoals` 与 `ReadingStats`，避免目标与统计分别读取时因中间写入导致判断偏差
- `ReaderViewModel.kt` `checkGoalCompletion()` 改用单次 `getGoalsAndStats()`，并使用 `DateTimeFormatter` 生成当日日期键

#### 阅读统计计算健壮性
- `ComicExtras.kt` `streakDays()` / `weeklyMinutes()` / `monthlyMonths()` / `todayMinutes()` / `todayPages()` 全部改用 `LocalDate` 计算连续天数、周起始、月聚合与今日数据，避免 `Calendar` 滚动与格式化的边界误差
- `SettingsRepository.kt` `pruneOldDates()` / `pruneOldDateSet()` 使用 `LocalDate` 比较裁剪阈值，夜间判断改用 `LocalDateTime.now().hour`

#### 统计图表性能
- `StatisticsScreen.kt` `WeeklyTrendChart` 中 X 轴标签与最大值标注的 `android.graphics.Paint` 对象提到 `remember` 中缓存，避免每次 Canvas 重绘重复创建原生 Paint

#### 缓存生命周期
- `PageCacheManager.kt` `shutdown()` 在关闭磁盘缓存与协程作用域前先 `memoryCache.evictAll()` 与 `BitmapPool.clear()`，确保应用终止时 Bitmap 及时释放
- `ReaderViewModel.kt` `processedBitmapCache` 淘汰时将被 LRU 挤出的位图回收到 `BitmapPool`，减少后续图像处理重新分配的开销
- `ReaderViewModel.kt` `onCleared()` 使用 `runBlocking + withTimeoutOrNull(2s)` 等待 `progressJob` / `cachePageJob` / `panelJob` / `bookmarkJob` / `ttsJob` 完成后再关闭 `parserRef` / `provider`，降低 use-after-close 崩溃风险

#### WebDAV 同步安全
- `WebDavSync.kt` `uploadSyncData()` 在上传前校验 JSON 内容是否超过 `MAX_SYNC_DATA_BYTES`（10MB），防止异常膨胀的本地数据导致上传失败或流量浪费

#### 测试
- `./gradlew assembleDebug testDebugUnitTest` 通过，13 个单元测试全部成功

### 13.40 阅读器底部缩略图进度条 (2026-07-23)

#### 功能概述
- 新增「底部缩略图进度条」：在阅读界面底部常驻显示当前分卷所有页面的缩略图，当前页以主题色高亮边框标识，点击任意缩略图可直接跳转
- 进度条默认关闭，用户可在设置页或阅读器菜单中随时开启/关闭，避免干扰「内容优先，UI 隐形」的默认阅读体验

#### 数据模型与持久化
- `ReaderSettings.kt`（位于 `SettingsRepository.kt`）新增 `thumbnailStripEnabled: Boolean = false`
- `SettingsRepository.kt` 新增 `KEY_THUMBNAIL_STRIP` DataStore 键，并在 `parseReaderSettings()` / `saveReaderSettingsToPreferences()` 中读写该字段
- 恢复阅读默认设置时 `thumbnailStripEnabled` 自动回退为 `false`

#### 阅读器菜单
- `ReaderViewModel.kt` 新增 `toggleThumbnailStrip()`，通过 `updateSettings(needsReload = false)` 持久化开关状态
- `ReaderScreen.kt` 的 `MinimalBottomBar` 新增缩略图进度条开关按钮，图标在活动状态下使用主题色，提供即时视觉反馈
- 开关按钮同时适配平板/折叠屏的大屏侧边菜单布局与手机的底部弹出菜单布局

#### 底部缩略图条 UI
- `ReaderScreen.kt` 新增 `ThumbnailStrip` 与 `ThumbnailStripItem` 组合件
- 条带高度 72dp，半透明背景，缩略图尺寸 42dp×60dp，避免遮挡主要内容
- 当前页自动滚动到可视区域，并支持 `animateScrollToItem` 平滑跟随
- RTL（日漫）模式下通过 `CompositionLocalProvider(LocalLayoutDirection)` 将条带从右向左排列，使第 1 页位于右侧，符合日漫阅读顺序
- 缩略图加载复用 `ReaderViewModel.loadThumbnail(index, 120)`，走 PageCacheManager 的 L1/L2 缓存，避免重复解码
- 仅当 `!showMenu && !panelState.isActive && totalPages > 0` 时显示，防止与菜单/分镜模式重叠

#### 设置页
- `SettingsScreen.kt` 在阅读设置分组中新增「底部缩略图进度条」开关
- `strings.xml` 新增 `settings_thumbnail_strip` / `settings_thumbnail_strip_desc` 文案

#### 测试
- `./gradlew assembleDebug testDebugUnitTest` 通过，13 个单元测试全部成功

### 13.41 缓存、阅读器状态与解析器健壮性修复 (2026-07-23)

#### 缩略图缓存与清理
- [PageCacheManager.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/cache/PageCacheManager.kt#L93)
  - 新增独立的 `thumbMemoryCache`（8MB LRU），专门缓存缩略图，避免大卷快速滚动时反复磁盘解码。
  - `getThumbnail` 向 UI 返回安全副本，自身保留原图，防止 UI 回收后污染缓存。
  - `getThumbnail` 写入 `thumbDiskCache` 后调用 `addToDiskKeyIndex(key)`，使 `clearByCacheId` 能正确按漫画清理缩略图磁盘缓存。
  - 新增 `thumbLoadLocks`，保证同一 `(cacheId, index, dim)` 的缩略图只并发解码一次。
  - 缩略图回退路径（`provider.getPage` → `scaleBitmapForThumb`）用 `runCatching` 包裹，异常时确保回收全尺寸 Bitmap。
  - `clearMemory()`、`clearMemoryCache()`、`shutdown()`、`clearByCacheId()` 均同步清理缩略图内存缓存与加载锁。

#### 阅读器状态与设置持久化
- [ReaderViewModel.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/reader/ReaderViewModel.kt#L1400)
  - `updateSettings` 增加 `settingsUpdateLock` 同步锁，保护「读-改-写」三步的原子性。
  - 对 `needsReload = false` 的后处理设置变更（亮度/对比度等），改用 `scheduleClearProcessedCache()` 做 250ms 防抖清空，避免滑条连续拖动时反复重建处理后缓存。
  - `needsReload = true` 的页面几何/解码方式变更仍立即清空缓存。
- [SettingsRepository.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/data/SettingsRepository.kt#L374)
  - `saveAsync` 在 `dataStore.edit` 块内检查 `currentCoroutineContext().isActive`，避免取消后仍写入过期快照。
  - `flushSave()` 改为带 `settings` 参数：先取消并等待旧任务，再同步保存最新设置，确保调用方落盘语义。
- [GestureSettingsScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/settings/GestureSettingsScreen.kt#L59)
  - 更新 `flushSave(newSettings)` 调用以匹配新签名。

#### 底部缩略图进度条优化
- [ReaderScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/reader/ReaderScreen.kt#L2350)
  - `ThumbnailStrip` 的 `LazyListState` 以 `rtl` 和 `totalPages` 为 key 重建，避免 RTL 切换后滚动方向/对齐异常。
  - 增加 `navigationBarsPadding()`，避免缩略图条覆盖系统手势导航区域。
  - `ThumbnailStripItem` 移除冗余的 `remember(index)`，依赖 Lazy 的 `items(key = { it })` 管理组合边界。

#### 解析器与 BitmapPool
- [EpubParser.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/parser/EpubParser.kt#L42)
  - 缩小写锁范围：仅在打开/清理 zip 与写入 `entries` 时持有写锁；图片路径扫描、HTML 文本提取改持读锁，避免大 EPUB 解析长时间阻塞 `getPageBytes` 并发读取。
- [ComicBookParser.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/parser/ComicBookParser.kt#L173)
  - 目录回退到 `FolderParser` 时，将该实例加入 `innerParsers`，确保 `close()` 统一关闭。
- [CbtParser.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/parser/CbtParser.kt#L154)
  - `buildIndex` 增加 `MAX_INDEX_ENTRIES = 100_000` 上限，超过时抛出异常，防止恶意/损坏 TAR 导致长时间扫描或内存溢出。
- [BitmapPool.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/utils/BitmapPool.kt#L71)
  - `bytesPerPixel` 对 `Bitmap.Config.HARDWARE` 返回 `Int.MAX_VALUE`，避免不可变的 Hardware Bitmap 被误匹配复用。
  - 在 KDoc 中强化「池中 Bitmap 严禁直接交给 UI 绘制」的约定注释。

#### 版本
- `versionCode` 33，`versionName` 1.0.33

#### 测试
- `./gradlew assembleDebug testDebugUnitTest` 通过，13 个单元测试全部成功

---

### 13.42 缓存关闭、WebDAV 同步批量写入与退出内存释放优化 (2026-07-23)

#### 磁盘缓存生命周期
- [DiskLruCache.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/cache/DiskLruCache.kt#L198)
  - `shutdown()` 新增清空内存中的 `accessTimeIndex`，避免缓存关闭后仍保留对文件条目的引用。
  - `shutdown()` 调用 `trimExecutor.shutdown()` 后等待最多 2 秒终止，确保后台 trim 线程安全退出。

#### 缓存管理器关闭后防护
- [PageCacheManager.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/cache/PageCacheManager.kt#L168)
  - `setCurrentPage`、`getPage`、`getThumbnail`、`preload` 在入口处检查 `isShutdown`，防止应用退出后继续发起解码/预读任务导致异常或资源泄漏。

#### 设置仓库批量写入
- [SettingsRepository.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/data/SettingsRepository.kt#L504)
  - 新增 `saveProgressBatch(progressMap, totalMap)`：将所有同步过来的进度、总页数、更新时间和追踪 URI 合并为一次 DataStore 事务写入。
  - 新增 `mergeBookmarks(uriToPages)`：批量合并多漫画书签，避免单条 add 触发多次整 Map 序列化。
  - 新增 `addFavorites(uris)`：批量添加收藏，仅在实际有变化时才写入。

#### WebDAV 同步 Worker 批量应用
- [WebDavSyncWorker.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/utils/WebDavSyncWorker.kt#L136)
  - `applyMergedData` 改为先批量写入进度、书签、收藏；批量写入失败时再降级为单条写入，兼顾性能与容错。
  - 减少同步大量漫画时的 DataStore 事务次数，降低 ANR 风险。

#### 阅读器退出内存释放
- [ReaderViewModel.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/reader/ReaderViewModel.kt#L869)
  - `onCleared()` 中提前调用 `processedBitmapCache.evictAll()`，在 2 秒 runBlocking 等待前释放处理后位图，降低退出时内存峰值。
  - 移除末尾重复的 `processedBitmapCache.evictAll()`。

#### 版本
- `versionCode` 34，`versionName` 1.0.34

#### 测试
- `./gradlew assembleDebug testDebugUnitTest` 通过，13 个单元测试全部成功

---

### 13.43 解析器单条目上限、缓存释放顺序与同步竞态修复 (2026-07-23)

#### 压缩归档单条目读取上限
- [CbzParser.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/parser/CbzParser.kt#L104)
  - `getPageBytes` / `getPageInputStream` 改为使用 `readBytesLimited(MAX_ENTRY_BYTES)`，`MAX_ENTRY_BYTES = 100MB`。
  - 捕获 `IOException` 单独记录「条目超过大小上限」，避免恶意或损坏 CBZ 中的超大图片导致 OOM。
- [CbrParser.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/parser/CbrParser.kt#L108)
  - `getEntryBytes` / `openEntry` 同样使用 `readBytesLimited(MAX_ENTRY_BYTES)`，并新增 `MAX_ENTRY_BYTES = 100MB`。
- [Cb7Parser.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/parser/Cb7Parser.kt#L109)
  - `getEntryBytes` 使用 `readBytesLimited(MAX_ENTRY_BYTES)`，为 7z 归档补充单文件大小限制。

#### 阅读器退出时缓存释放顺序
- [ReaderViewModel.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/reader/ReaderViewModel.kt#L869)
  - `onCleared()` 调整资源释放顺序：先停止 TTS/自动翻页，再 `runBlocking` 等待后台任务结束，最后关闭 parser/provider 并清空 `cacheManager` 与 `processedBitmapCache`。
  - 避免后台 `cachePageJob` 在 `processedBitmapCache.evictAll()` 之后仍写入新位图，导致退出时位图泄漏。

#### 设置仓库同步竞态修复
- [SettingsRepository.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/data/SettingsRepository.kt#L401)
  - `flushSave()` 在 `synchronized` 块内仅做「取消并返回待等待 Job」，将 `job.join()` 移出同步块，消除编译器对在临界区调用挂起函数的警告，并避免并发 `saveAsync` 在 `cancel` 与 `join` 之间产生新的竞态。

#### 缓存管理器关闭等待后台协程
- [PageCacheManager.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/cache/PageCacheManager.kt#L354)
  - `shutdown()` 在取消 `cacheScope` 后使用 `runBlocking + withTimeoutOrNull(2s)` 等待 `cacheScope` 结束，再关闭磁盘缓存。
  - 修复应用退出时磁盘缓存 `shutdown()` 可能与正在进行的预读/写入任务竞争的问题。

#### 分卷浏览器读取取消处理
- [ChapterBrowserViewModel.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/chapters/ChapterBrowserViewModel.kt#L230)
  - `readBytesLimited()` 改为挂起函数，循环内检查 `currentCoroutineContext().isActive`，避免大文件读取时协程取消不及时。

#### 解析器工具签名清理
- [ParserBitmapUtils.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/parser/ParserBitmapUtils.kt#L133)
  - `ByteArrayOutputStreamLimited.write(b: ByteArray?, off, len)` 改为非空 `ByteArray` 参数，消除 Kotlin 对 Java 重写给方法可空签名不匹配的编译警告（该警告将在后续版本变为错误）。

#### 版本
- `versionCode` 35，`versionName` 1.0.35

#### 测试
- `./gradlew assembleDebug testDebugUnitTest` 通过，13 个单元测试全部成功

---

### 13.44 WebDAV 时间戳冲突解决、同步失败通知与主题一致性 (2026-07-24)

#### WebDAV 同步按时间戳合并
- [WebDavSync.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/utils/WebDavSync.kt#L39)
  - `SyncData` 新增 `progressTimes: Map<String, Long>`，记录每条阅读进度的最后更新时间。
  - `mergeSyncData()` 改为按时间戳选择较新的进度；无时间戳的旧数据回退到取较大页码，避免远端旧进度覆盖本地新进度。
- [WebDavSyncWorker.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/utils/WebDavSyncWorker.kt#L92)
  - 自动同步时读取本地 `getRecentReads()` 作为 `progressTimes`，与进度一起上传。
  - 上传调用 `WebDavSync.uploadSyncData(..., merged.progressTimes)`，确保合并后的时间戳回写到服务器。
- [BookshelfExtras.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/bookshelf/BookshelfExtras.kt#L305)
  - 手动同步对话框同样读取本地进度时间并传入 `SyncData`，双向同步上传时保留合并后的时间戳。

#### 同步失败通知
- [NotificationUtils.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/utils/NotificationUtils.kt#L35)
  - 新增「同步状态」通知渠道（低优先级）。
  - 新增 `showSyncErrorNotification()`，Android 13+ 自动检查 `POST_NOTIFICATIONS` 权限。
- [WebDavSyncWorker.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/utils/WebDavSyncWorker.kt#L113)
  - 下载/上传阶段遇到 401/403 认证失败时，立即发送通知提示用户检查配置，并返回 `Result.failure()` 不再重试。

#### 主题与阅读页一致性
- [themes.xml](file:///d:/manhua2222/app/src/main/res/values/themes.xml#L3)
  - 将应用主题父类从 `android:Theme.Material.Light.NoActionBar` 改为 `android:Theme.Material.NoActionBar`，与应用深色主主题保持一致。
- [ReaderScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/reader/ReaderScreen.kt#L340)
  - 用户自定义阅读背景色解析失败时，回退色从硬编码 `Color.Black` 改为 `MaterialTheme.colorScheme.background`，保证主题切换时视觉一致。

#### 版本
- `versionCode` 36，`versionName` 1.0.36

#### 测试
- `./gradlew assembleDebug testDebugUnitTest` 通过，13 个单元测试全部成功

---

### 13.45 封面提取安全加固（大小上限与 OOM 防护）(2026-07-24)

#### 封面源文件大小限制
- [CoverExtractor.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/utils/CoverExtractor.kt#L38)
  - 新增 `MAX_COVER_ENTRY_BYTES = 50MB` 常量。
  - `decodeSampled()` 改用 [ParserBitmapUtils.readBytesLimited()](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/parser/ParserBitmapUtils.kt)，超过上限时抛出 `IOException` 并记录日志，返回 null 而非撑爆内存。
  - 统一覆盖 CBZ/ZIP、CBR/RAR、CB7/7Z、CBT/TAR、EPUB fallback 与本地图片等所有封面提取路径。

#### OOM 兜底
- [CoverExtractor.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/utils/CoverExtractor.kt#L114)
  - `extractPdfFirstPage()` 增加 `OutOfMemoryError` 捕获，防止超大 PDF 首页渲染时崩溃。
- [CoverExtractor.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/utils/CoverExtractor.kt#L500)
  - `extractLocalImageThumbnail()` 同样捕获 `OutOfMemoryError`。

#### 版本
- `versionCode` 36，`versionName` 1.0.36（与 13.44 同版本迭代）。

#### 测试
- `./gradlew assembleDebug testDebugUnitTest` 通过，13 个单元测试全部成功

---

### 13.46 崩溃日志总大小限制、历史 URI 解析日志与 WebDAV 合并单元测试 (2026-07-24)

#### 崩溃日志目录总大小限制
- [CrashHandler.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/utils/CrashHandler.kt#L24)
  - 新增 `MAX_TOTAL_LOG_SIZE_BYTES = 2MB`，即使单文件被截断到 256KB，长期累积仍可能占用存储。
  - [rotateLogs()](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/utils/CrashHandler.kt#L106) 在按数量轮转后，再按总大小删除最旧日志，确保目录总占用不超过 2MB。

#### 历史记录 URI 解析失败日志
- [HistoryScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/history/HistoryScreen.kt#L160)
  - 点击历史条目时若 `Uri.parse()` 失败，现在会记录 `Log.w` 并展示条目被静默忽略的原因，便于排查脏数据。

#### WebDAV 合并单元测试
- [WebDavSyncTest.kt](file:///d:/manhua2222/app/src/test/java/com/mangareader/utils/WebDavSyncTest.kt)
  - 新增 6 个单元测试覆盖 `mergeSyncData`：
    - 无时间戳时按最大页码合并；
    - 远端时间戳较新时采用远端进度；
    - 本地时间戳较新时保留本地进度；
    - 远端无时间戳时保留本地；
    - 书签去重合并与收藏并集；
    - 本地/远端独立条目的时间戳合并。

#### 版本
- `versionCode` 36，`versionName` 1.0.36。

#### 测试
- `./gradlew assembleDebug testDebugUnitTest` 通过，单元测试从 13 个增加到 19 个。

---

### 13.47 Lint 清零与系统主题一致性完善 (2026-07-24)

#### 目标
继续全面审查项目，将 Lint 报告从 4 个警告清零，并完善系统级主题资源，确保启动、配置变更与夜间模式下的视觉一致性。

#### Lint 警告清零
- [app/build.gradle.kts](file:///d:/manhua2222/app/build.gradle.kts#L57)
  - 新增 `lint { disable += "ObsoleteLintCustomCheck" }`，屏蔽由 Compose Runtime 库旧版 lint.jar 引入的无效 API 警告（非项目代码可修复）。
- [AndroidManifest.xml](file:///d:/manhua2222/app/src/main/AndroidManifest.xml#L9)
  - 为 `WRITE_EXTERNAL_STORAGE`（`maxSdkVersion="29"`）显式添加 `tools:ignore="ScopedStorage"`，说明应用使用 SAF 进行实际文件操作，该权限仅用于 Android 10 及以下兼容。
  - 修正 `application` 标签的 `tools:ignore` 为 `UnusedAttribute,DataExtractionRules`，完整覆盖 `dataExtractionRules` 相关的两条 Lint 规则。
  - 移除无意义的 `tools:replace="android:testOnly"`，消除 manifest merger 的冗余提示。
- 资源目录整理
  - 将 `mipmap-anydpi-v26/ic_launcher.xml` 移至 `mipmap/ic_launcher.xml` 并删除空目录，解决 `ObsoleteSdkInt` 警告（`minSdk` 已为 26，`-v26` 限定符冗余）。
  - 新增 [full_backup_content.xml](file:///d:/manhua2222/app/src/main/res/xml/full_backup_content.xml)，配合 `android:fullBackupContent` 满足 `DataExtractionRules` 对 Android 12 以下备份配置的校验要求，同时保持 `allowBackup="false"` 的零备份策略。

#### 系统主题一致性
- [themes.xml](file:///d:/manhua2222/app/src/main/res/values/themes.xml)
  - 将状态栏与导航栏颜色设为透明，配合 `enableEdgeToEdge()` 实现全屏沉浸式体验。
  - 保持深色 `windowBackground`，避免浅色闪屏。
- 新增 [values-night/themes.xml](file:///d:/manhua2222/app/src/main/res/values-night/themes.xml)
  - 为夜间模式提供与日间一致的深色启动主题，防止系统在暗光环境下使用浅色启动背景造成视觉突兀。

#### 版本
- `versionCode` 37，`versionName` 1.0.37。

#### 测试
- `./gradlew assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：`No errors or warnings`。
- 单元测试保持 19 个全部通过。

---

### 13.48 备份纳入阅读时间戳与崩溃日志 UTF-8 安全截断 (2026-07-24)

#### 目标
本轮审查聚焦备份/恢复一致性与崩溃日志健壮性：确保本地备份能完整保留 WebDAV 同步冲突解决所需的时间戳信息；修复崩溃日志超长截断时可能切断 UTF-8 多字节字符的问题。

#### 本地备份支持阅读时间戳
- [SettingsRepository.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/data/SettingsRepository.kt#L145)
  - 备份版本从 1 提升至 2（`BACKUP_VERSION = 2`），`MIN_SUPPORTED_BACKUP_VERSION` 保持 1，确保旧备份仍可导入。
- [BackupData](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/data/SettingsRepository.kt#L1376)
  - 新增 `progressTimes: Map<String, Long>` 字段（默认 `emptyMap()`），用于保存每条阅读进度的最后更新时间戳。
  - 由于 `Json { ignoreUnknownKeys = true }`，旧版应用读取含新字段的 v2 备份时会自动忽略未知字段，保持前向兼容。
- [exportAllData()](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/data/SettingsRepository.kt#L1171)
  - 导出时通过 `getRecentReads().first()` 将阅读时间戳写入备份。
- [importAllData()](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/data/SettingsRepository.kt#L1189)
  - 导入时合并本地与备份中的 `progressTimes`，对同一 URI 保留较新的时间戳；覆盖模式则直接使用备份值。
  - 新增 `parseProgressTimes()` 与 `saveProgressTimesInternal()` 辅助方法，确保 tracked URIs 与对应时间戳保持一致。

#### 崩溃日志 UTF-8 安全截断
- [CrashHandler.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/utils/CrashHandler.kt#L71)
  - 重写日志截断逻辑：先将文本转为 UTF-8 字节数组，从目标上限处向前回退到最近的字符起始字节（`10xxxxxx` 之前的 `11xxxxxx`），再解码。
  - 避免旧逻辑按固定字节位置截断多字节字符，导致日志文件出现 � 或解码异常。

#### 版本
- `versionCode` 38，`versionName` 1.0.38。

#### 测试
- `./gradlew assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：`No errors or warnings`。
- 单元测试保持 19 个全部通过。

---

### 13.49 章节浏览键修复、手势保存反馈与缓存清理健壮性 (2026-07-24)

#### 目标
本轮审查聚焦章节浏览、手势设置与缓存管理三个子模块，修复因章节标题重复导致的缩略图状态共享问题，补充手势保存失败的用户反馈，并统一缓存清理逻辑以确保取消/异常时 UI 状态正确恢复。

#### 章节浏览缩略图键修复
- [ChapterBrowserScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/chapters/ChapterBrowserScreen.kt#L132)
  - `ChapterCard` 的 `remember` 与 `LaunchedEffect` 键从 `chapter.title` 改为 `chapter.uri`。
  - 避免因不同分卷标题相同而共享同一份 `thumb` 状态，导致缩略图显示错乱或重复加载。

#### 手势保存失败反馈
- [GestureSettingsScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/settings/GestureSettingsScreen.kt#L68)
  - 保存手势配置异常时，除了重置 `saving` 状态，还会弹出 `Toast` 提示用户保存失败。
- [strings.xml](file:///d:/manhua2222/app/src/main/res/values/strings.xml#L299)
  - 新增 `gesture_save_failed` 字符串资源。

#### 缓存清理健壮性
- [CacheManagementScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/settings/CacheManagementScreen.kt#L66)
  - 新增 `clearCache(block)` 辅助函数，统一封装 `isClearing` 状态、IO 清理、刷新大小与最终复位。
  - 使用 `try/finally` 保证即使协程取消或清理过程抛出异常，`isClearing` 也能正确复位，避免按钮永久不可用。
  - 页面缓存、缩略图缓存、分享卡片、解析器临时文件与一键清空全部改用该辅助函数。

#### 版本
- `versionCode` 39，`versionName` 1.0.39。

#### 测试
- `./gradlew assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：`No errors or warnings`。
- 单元测试保持 19 个全部通过。

---

### 13.50 外部 Intent 导航、WebDAV 认证判断与同步时间戳修复 (2026-07-24)

#### 目标
本轮审查覆盖应用入口导航、WebDAV 同步 Worker 与数据层，修复外部文件打开时可能产生的章节浏览页堆叠问题，增强 WebDAV 认证失败判断的兼容性，并补全同步后阅读时间戳的落盘逻辑，避免后续冲突解决依据不一致。

#### 外部 Intent 导航堆叠修复
- [MangaReaderApp.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/MangaReaderApp.kt#L105)
  - 外部分享/Intent 打开漫画时，`navigate(Screen.ChapterBrowser.route)` 增加 `popUpTo(Screen.Bookshelf.route) { inclusive = false }` 与 `launchSingleTop = true`。
  - 避免用户在已打开章节浏览/阅读器等页面时，新 Intent 再压入一个章节浏览页，导致返回栈多层堆叠、返回行为不符合预期。

#### WebDAV 认证失败判断增强
- [WebDavSyncWorker.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/utils/WebDavSyncWorker.kt#L160)
  - `isAuthFailure()` 除原有中文 "认证失败" 前缀外，额外检查错误信息中是否包含 "401" 或 "403"。
  - 提升对多语言服务器返回或异常封装的容错能力，确保认证类错误及时终止重试并通知用户。

#### 同步后阅读时间戳落盘
- [SettingsRepository.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/data/SettingsRepository.kt#L537)
  - 新增 `saveProgressTimesBatch(times: Map<String, Long>)`，将合并后的 `progressTimes` 批量写入 DataStore。
  - 同步过程中 `mergeSyncData()` 依赖时间戳进行冲突仲裁，但此前 `applyMergedData()` 未将合并后的时间戳持久化，导致本地时间戳与合并结果不一致。
- [WebDavSyncWorker.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/utils/WebDavSyncWorker.kt#L221)
  - 在 `applyMergedData()` 末尾调用 `repo.saveProgressTimesBatch(merged.progressTimes)`，保证进度、总页数、书签、收藏、时间戳全部落盘。

#### 版本
- `versionCode` 40，`versionName` 1.0.40。

#### 测试
- `./gradlew assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：`No errors or warnings`。
- 单元测试保持 19 个全部通过。

---

### 13.51 设置保存互斥锁、章节浏览 Lazy 键与同步时间戳落盘 (2026-07-24)

#### 目标
本轮审查聚焦阅读设置持久化、分卷浏览列表状态与 WebDAV 同步数据一致性，修复 `saveAsync` / `flushSave` 之间的写入竞态，避免不同章节标题相同导致 Lazy 列表状态复用异常，并补全同步后阅读时间戳的落盘逻辑。

#### 阅读设置保存互斥锁
- [SettingsRepository.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/data/SettingsRepository.kt#L157)
  - 将 `saveJobLock` 从 `synchronized(Any())` 替换为 `Mutex`。
  - `saveAsync()` 在 `repositoryScope` 中通过 `saveJobLock.withLock` 取消旧任务并创建新延迟写入任务。
  - `flushSave()` 在 `withLock` 块内完成：取消/等待旧任务、清空 `saveJob`、立即执行 `dataStore.edit` 写入。
  - 解决 `flushSave` 在等待旧任务与真正写入之间，新的 `saveAsync` 可能插入延迟写入、导致过期快照覆盖即时保存的问题。

#### 章节浏览 Lazy 项键修复
- [ChapterBrowserScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/chapters/ChapterBrowserScreen.kt#L107)
  - `LazyVerticalGrid` 的 `items` 键从 `key = { it.title }` 改为 `key = { it.uri.toString() }`。
  - 避免不同分卷标题相同时，Lazy 列表将多个章节视为同一项而错误复用组合状态或丢失条目。

#### 同步时间戳落盘
- [SettingsRepository.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/data/SettingsRepository.kt#L537)
  - 新增 `saveProgressTimesBatch(times: Map<String, Long>)`，将合并后的阅读时间戳批量写入 DataStore 并维护追踪 URI 列表。
- [WebDavSyncWorker.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/utils/WebDavSyncWorker.kt#L221)
  - `applyMergedData()` 末尾调用 `saveProgressTimesBatch(merged.progressTimes)`，保证冲突仲裁使用的时间戳在本地持久化。

#### WebDAV 认证失败判断增强（延续）
- [WebDavSyncWorker.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/utils/WebDavSyncWorker.kt#L160)
  - `isAuthFailure()` 继续兼容中文前缀，并额外检查错误信息中是否包含 `401` / `403`。

#### 版本
- `versionCode` 41，`versionName` 1.0.41。

#### 测试
- `./gradlew assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：`No errors or warnings`。
- 单元测试保持 19 个全部通过。

---

### 13.52 压缩包条目路径规范化、外部 Intent 类型提示与恢复空文件防护 (2026-07-24)

#### 目标
本轮审查聚焦解析器安全、外部文件打开体验与设置恢复健壮性：对 CBZ/EPUB 压缩包条目名统一做路径规范化，阻断潜在的路径遍历并统一反斜杠分隔符；当外部 Intent 传入无法识别的文件类型时给出明确提示；恢复备份时拦截空文件，避免进入解析流程产生误导性失败提示。

#### 压缩包条目名路径规范化
- [ComicParser.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/parser/ComicParser.kt#L25)
  - 新增 `normalizeArchiveEntryName(name: String): String?` 顶层函数。
  - 将反斜杠统一替换为正斜杠，解析 `.` 与 `..` 段；若路径试图越出根目录或结果为空，返回 null。
- [CbzParser.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/parser/CbzParser.kt#L62)
  - 解析阶段以规范化后的名称为键，建立「规范名称 → 原始 ZipArchiveEntry」映射。
  - `getPageBytes()` / `getPageInputStream()` 先规范化入参条目名，再进行查找，防御调用侧传入异常路径。
- [EpubParser.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/parser/EpubParser.kt#L51)
  - 第一遍扫描时即建立规范名称到原始 ZipEntry 的映射。
  - `resolveImageOrder()` 返回的 OPF 路径同样经过规范化并过滤掉无法匹配到实际条目的路径。
  - 读取入口统一规范化后再查询 `entries` 或 `ZipFile.getEntry()`。

#### 外部 Intent 未知类型提示
- [MangaReaderApp.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/MangaReaderApp.kt#L95)
  - 当扩展名与 MIME 均无法识别为支持的漫画类型时，弹出 Toast 提示用户选择 CBZ/CBR/CB7/CBT/EPUB/PDF 格式。
- [strings.xml](file:///d:/manhua2222/app/src/main/res/values/strings.xml#L407)
  - 新增 `external_intent_unknown_type_toast` 字符串资源。

#### 恢复空备份文件防护
- [SettingsScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/settings/SettingsScreen.kt#L111)
  - 读取备份文件内容后，若 JSON 为空或仅空白字符，直接提示「备份文件解析失败」并返回，不再调用 `importAllData()`。

#### 版本
- `versionCode` 42，`versionName` 1.0.42。

#### 测试
- `./gradlew assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：`No errors or warnings`。
- 单元测试保持 19 个全部通过。

---

### 13.53 补充压缩包条目规范化单元测试 (2026-07-24)

#### 目标
为上一轮新增的 `normalizeArchiveEntryName` 提供完整单元测试，覆盖正常路径、分隔符统一、当前目录/父目录段解析、路径遍历防御及空值边界，防止后续重构破坏解析器安全行为。

#### 新增测试
- [ComicParserTest.kt](file:///d:/manhua2222/app/src/test/java/com/mangareader/parser/ComicParserTest.kt)
  - `keeps normal forward slash paths`：验证普通正斜杠路径保持不变。
  - `converts backslashes to forward slashes`：验证反斜杠统一替换为正斜杠。
  - `removes current directory segments`：验证 `.` 段被正确移除。
  - `resolves parent directory segments`：验证 `..` 段正确回退一级目录。
  - `rejects traversal above root`：验证试图越出根目录的 `..` 返回 null。
  - `rejects empty and blank names`：验证空字符串、全空白、仅分隔符返回 null。
  - `handles mixed separators and redundant segments`：验证混合分隔符与冗余段组合场景。

#### 版本
- `versionCode` 43，`versionName` 1.0.43。

#### 测试
- `./gradlew assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：`No errors or warnings`。
- 单元测试从 19 个增加到 26 个，全部通过。

---

### 13.54 新增「阅读时屏幕常亮」功能 (2026-07-24)

#### 目标
为阅读界面新增屏幕常亮控制，避免用户在长时间阅读时因系统自动息屏而中断体验；同时提供设置开关，允许用户按个人习惯启用或关闭。

#### 数据层
- [SettingsRepository.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/data/SettingsRepository.kt#L125)
  - 新增 `KEY_KEEP_SCREEN_ON` DataStore 布尔键。
  - 在 `readerSettingsFromPreferences()` 中读取该键，默认值为 `true`（与漫画阅读常见需求一致）。
  - 在 `saveReaderSettingsToPreferences()` 中持久化该值。
- [SettingsRepository.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/data/SettingsRepository.kt#L1494)
  - `ReaderSettings` 新增 `keepScreenOn: Boolean = true` 字段；由于使用 `@Serializable` 且字段有默认值，旧备份恢复时缺失该字段会自动回退到默认值。

#### UI 层
- [SettingsScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/settings/SettingsScreen.kt#L264)
  - 在阅读设置分组中新增「阅读时保持屏幕常亮」SwitchItem，开关变化时通过 `repo.saveAsync()` 异步保存。
- [strings.xml](file:///d:/manhua2222/app/src/main/res/values/strings.xml#L143)
  - 新增 `settings_keep_screen_on` 与 `settings_keep_screen_on_desc` 字符串资源。

#### 阅读器层
- [ReaderScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/reader/ReaderScreen.kt#L312)
  - 使用 `LaunchedEffect(state.settings.keepScreenOn)` 动态添加/清除 `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON`。
  - 在 `DisposableEffect(Unit)` 的 `onDispose` 中清除屏幕常亮标志，确保退出阅读器后不影响书架等其他页面。

#### 版本
- `versionCode` 44，`versionName` 1.0.44。

#### 测试
- `./gradlew assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：`No errors or warnings`。
- 单元测试 26 个全部通过。

---

### 13.55 阅读页顶部状态栏增加本次阅读时长 (2026-07-24)

#### 目标
在阅读页顶部状态栏中展示当前阅读会话的持续时长，让用户无需离开阅读界面即可了解本次阅读耗时；同时复用已有的每分钟时间刷新机制，避免引入额外的秒级重组开销。

#### 实现
- [ReaderViewModel.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/reader/ReaderViewModel.kt#L160)
  - 新增 `currentSessionDurationMs(): Long`，基于 `@Volatile` 的 `readingSessionStart` 与原子标志 `readingSessionActive` 计算已持续毫秒数；未进入阅读会话时返回 0。
- [ReaderScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/reader/ReaderScreen.kt#L625)
  - `ReaderStatusBar` 新增 `viewModel: ReaderViewModel` 参数。
  - 在状态栏行中插入本次阅读时长文本，使用 `remember(timeTick)` 与现有每分钟刷新对齐，减少不必要的重组。
  - 新增 `formatSessionDuration()` 辅助函数：不足 1 小时显示 `MM:SS`；1 小时及以上显示 `H:MM:SS`。

#### 行为
- 阅读会话开始时（`loadComic` / `loadPages` 设置 `readingSessionStart`）自动开始计时。
- 退出阅读器或 ViewModel 清理时，`readingSessionActive` 被原子置为 false，时长显示随即消失。
- 仅在「阅读页状态栏信息」开关开启时显示，与该开关共存，保持 UI 隐形原则。

#### 版本
- `versionCode` 45，`versionName` 1.0.45。

#### 测试
- `./gradlew assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：`No errors or warnings`。
- 单元测试 26 个全部通过。

### 13.56 翻页动画高级效果实现 (2026-07-24)

#### 目标
为设置页中已有的「像素渐变」「百叶窗」「碎裂」「漫画网点」四种翻页动画选项提供真实的 Compose 过渡效果，并清理早期遗留的未使用 `PageTransition` 实现，减少代码冗余。

#### 实现
- [ReaderScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/reader/ReaderScreen.kt#L1133)
  - 为 `PageAnimation.PIXEL` 添加 `scaleIn` + `scaleOut` + `fadeIn/fadeOut` 组合过渡，模拟像素缩放切换。
  - 为 `PageAnimation.BLINDS` 添加 `slideInVertically` + `slideOutVertically` 组合过渡，模拟百叶窗切入切出。
  - 为 `PageAnimation.SHATTER` 添加 `scaleIn` + `shrinkOut` 组合过渡，模拟碎裂收缩消失。
  - 为 `PageAnimation.DOTS` 添加 `expandIn` + `fadeOut` 组合过渡，模拟网点从中心扩散。
  - 所有高级动画均受 `pageAnimSpeed` 控制，并复用已有的 `animationKey` 与 `graphicsLayer` 缩放/平移逻辑。
- 删除 `app/src/main/kotlin/com/mangareader/reader/ui/PageAnimation.kt`
  - 移除早期遗留的 `PageTransition` 枚举与 `PageTransitionContainer` 函数，该实现未被任何代码引用。

#### 行为
- 在阅读设置中选择「像素渐变」「百叶窗」「碎裂」「漫画网点」后，翻页时会看到与选项名称对应的过渡动画，不再回退为简单的淡入淡出。
- 动画速度、RTL 方向、`randomAnimation` 随机模式等现有行为保持不变。

#### 版本
- `versionCode` 46，`versionName` 1.0.46。

#### 测试
- `./gradlew assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：`No errors or warnings`。
- 单元测试 26 个全部通过。

### 13.57 阅读统计分享功能 (2026-07-24)

#### 目标
为阅读统计页面补充系统分享能力，允许用户一键将累计页数、读完本数、连续天数、今日/累计时长、平均速度及目标进度生成文本报告并发送给其他应用。

#### 实现
- [StatisticsScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/stats/StatisticsScreen.kt#L69)
  - 在 `TopAppBar` 右侧新增分享图标按钮。
  - 新增 `shareStatistics()` 函数，将 `ReadingStats` 与 `ReadingGoals` 格式化为纯文本报告。
  - 使用 `Intent.ACTION_SEND` + `Intent.createChooser` 调用系统分享面板，无需额外权限。
- [strings.xml](file:///d:/manhua2222/app/src/main/res/values/strings.xml#L369)
  - 新增 `statistics_share_title`、`statistics_share_chooser`、`statistics_share_subject`、`statistics_share_header` 等分享专用字符串。
  - 新增统计项格式化字符串：`statistics_share_total_pages`、`statistics_share_comics_finished`、`statistics_share_streak_days`、`statistics_share_today_minutes`、`statistics_share_today_pages`、`statistics_share_total_minutes`、`statistics_share_avg_speed`、`statistics_share_avg_daily`、`statistics_share_goal_pages`、`statistics_share_goal_minutes`。

#### 行为
- 进入「阅读统计」页面后，右上角出现分享图标。
- 点击后弹出系统分享面板，文本内容包含当前所有核心统计数据及今日目标进度（若已设置目标）。
- 用户可选择复制到剪贴板、发送给社交应用或邮件等。

#### 版本
- `versionCode` 47，`versionName` 1.0.47。

#### 测试
- `./gradlew assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：`No errors or warnings`。
- 单元测试 26 个全部通过。

### 13.58 阅读历史按日期分组 (2026-07-24)

#### 目标
优化阅读历史页面的信息组织方式，将历史记录按「今天 / 昨天 / 本周 / 本月 / 更早」自动分组，提升长历史列表的可读性。

#### 实现
- [HistoryScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/history/HistoryScreen.kt#L153)
  - 在 `LazyColumn` 渲染前，使用 `remember(filteredHistory, context)` 按日期对过滤后的历史记录做分组。
  - 分组规则：今天、昨天、本周（周一至今）、本月（1 日至今）、更早。
  - `LazyColumn` 内先渲染分组标题 `Text`，再渲染该组下的 `HistoryItem`。
  - 搜索过滤后分组自动跟随变化。
- [strings.xml](file:///d:/manhua2222/app/src/main/res/values/strings.xml#L67)
  - 新增 `history_group_today`、`history_group_yesterday`、`history_group_this_week`、`history_group_this_month`、`history_group_earlier`。

#### 行为
- 阅读历史不再是一大段连续列表，而是以分组标题间隔展示。
- 分组标题使用 `titleSmall` + 主题色，便于快速定位。
- 清空历史、搜索无结果等空状态保持不变。

#### 版本
- `versionCode` 48，`versionName` 1.0.48。

#### 测试
- `./gradlew assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：`No errors or warnings`。
- 单元测试 26 个全部通过。

### 13.59 阅读历史分享功能 (2026-07-24)

#### 目标
为阅读历史页面补充系统分享能力，让用户可以一键导出最近阅读过的漫画列表，包含标题、进度和阅读时间，便于备份或分享。

#### 实现
- [HistoryScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/history/HistoryScreen.kt#L127)
  - 在 `TopAppBar` 右侧新增分享图标按钮。
  - 新增 `shareHistory()` 函数，将历史记录格式化为带序号的纯文本报告。
  - 为避免报告过长，仅导出最近 50 条记录。
  - 使用 `Intent.ACTION_SEND` + `Intent.createChooser` 调用系统分享面板。
  - 新增 `formatHistoryShareTime()` 辅助函数统一时间格式化。
- [strings.xml](file:///d:/manhua2222/app/src/main/res/values/strings.xml#L72)
  - 新增 `history_share_title`、`history_share_chooser`、`history_share_subject`、`history_share_header`、`history_share_count`、`history_share_progress`、`history_share_time`。

#### 行为
- 进入「阅读历史」页面后，右上角出现分享图标（历史非空时）。
- 点击后弹出系统分享面板，文本内容包含历史总数及每条记录的标题、进度、时间。
- 用户可选择复制到剪贴板、发送给社交应用或邮件等。

#### 版本
- `versionCode` 49，`versionName` 1.0.49。

#### 测试
- `./gradlew assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：`No errors or warnings`。
- 单元测试 26 个全部通过。

### 13.60 章节浏览器搜索功能 (2026-07-24)

#### 目标
为章节浏览器添加实时搜索能力，方便用户在分卷较多的漫画中快速定位目标章节。

#### 实现
- [ChapterBrowserScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/chapters/ChapterBrowserScreen.kt#L47)
  - 新增搜索状态 `isSearching`、`searchQuery`、`focusRequester`。
  - 新增 `filteredChapters`，按章节标题对关键词进行不区分大小写匹配。
  - `TopAppBar` 支持在普通标题栏和搜索输入框之间切换：
    - 点击右上角搜索图标进入搜索模式
    - 搜索模式下返回键用于关闭搜索
    - 输入框带清空按钮
  - 使用 `LaunchedEffect(isSearching)` 在切到搜索模式时自动请求焦点。
  - 网格渲染使用 `filteredChapters`，无匹配时展示空提示。
- [strings.xml](file:///d:/manhua2222/app/src/main/res/values/strings.xml#L534)
  - 新增 `chapter_browser_search_hint`、`chapter_browser_no_search_match`。

#### 行为
- 进入章节浏览器，当分卷数大于 1 时右上角显示搜索图标。
- 点击后顶部标题栏替换为搜索输入框，输入时即时过滤下方分卷卡片。
- 搜索无结果时显示「未找到匹配的章节」。
- 返回键关闭搜索并清空关键词。

#### 版本
- `versionCode` 50，`versionName` 1.0.50。

#### 测试
- `./gradlew assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：`No errors or warnings`。
- 单元测试 26 个全部通过。

### 13.61 书架视图模式持久化 (2026-07-24)

#### 目标
将书架的「网格 / 列表」视图模式持久化到 DataStore，使用户在应用重启或配置变更后仍保持上次选择的视图方式。

#### 实现
- [SettingsRepository.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/data/SettingsRepository.kt#L126)
  - 新增 `KEY_BOOKSHELF_VIEW_MODE` 字符串偏好键。
  - 新增 `getBookshelfViewMode()`：以 `Flow<String>` 返回已保存的视图模式，默认 `"GRID"`。
  - 新增 `saveBookshelfViewMode(mode: String)`：在 IO 调度器下写入 DataStore。
- [BookshelfScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/bookshelf/BookshelfScreen.kt#L133)
  - 视图模式 `viewMode` 仍使用 `rememberSaveable` + 自定义 `BookshelfViewModeSaver`，以在配置变更时即时恢复。
  - 新增 `LaunchedEffect(Unit)`：启动时从 DataStore 读取最近一次保存的模式，异常时安全回退到默认值。
  - 新增 `LaunchedEffect(viewMode)`：每次用户切换视图时异步写入 DataStore。

#### 行为
- 书架默认以网格视图展示。
- 用户在顶部工具栏切换网格 / 列表后，下次启动应用仍保持该视图。
- 屏幕旋转等配置变更期间，`rememberSaveable` 保证不闪烁回默认视图。
- 若读取持久化值失败或保存了未识别的枚举名，自动回退到网格视图，避免崩溃。

#### 版本
- `versionCode` 51，`versionName` 1.0.51。

#### 测试
- `./gradlew assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：`No errors or warnings`。
- 单元测试 26 个全部通过。

### 13.62 书架筛选、排序与当前分类持久化 (2026-07-24)

#### 目标
将书架的筛选条件、排序方式以及当前选中的分类一并持久化到 DataStore，与视图模式持久化共同构成完整的书架浏览状态记忆，避免每次重启应用后重新调整。

#### 实现
- [SettingsRepository.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/data/SettingsRepository.kt#L127)
  - 新增 `KEY_BOOKSHELF_FILTER`、`KEY_BOOKSHELF_SORT`、`KEY_BOOKSHELF_SELECTED_CATEGORY` 三个字符串偏好键。
  - 新增 `getBookshelfFilter()` / `saveBookshelfFilter(filter: String)`。
  - 新增 `getBookshelfSort()` / `saveBookshelfSort(sort: String)`。
  - 新增 `getBookshelfSelectedCategory()` / `saveBookshelfSelectedCategory(category: String?)`，清除分类时删除对应键。
- [BookshelfScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/bookshelf/BookshelfScreen.kt#L122)
  - 将 `filter`、`sortBy`、`selectedCategory` 与 `viewMode` 放在同一个 `LaunchedEffect(Unit)` 中从 DataStore 恢复。
  - 分别为四项状态新增 `LaunchedEffect` 写入 DataStore。
  - 新增 `LaunchedEffect(categories, selectedCategory)`：当已保存的分类被删除时自动清除，防止书架意外显示为空。

#### 行为
- 书架默认展示「全部 / 按名称排序 / 未选择分类」。
- 用户切换筛选（全部、收藏、未读等）、排序（名称、最近阅读、进度）或选择某个分类后，下次启动应用自动恢复。
- 若持久化值损坏或对应枚举被移除，自动回退到默认值，避免崩溃。
- 删除分类后，已保存的该分类过滤条件会自动失效并回到无分类过滤状态。

#### 版本
- `versionCode` 52，`versionName` 1.0.52。

#### 测试
- `./gradlew assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：`No errors or warnings`。
- 单元测试 26 个全部通过。

### 13.63 项目结构强化与书架搜索状态持久化 (2026-07-24)

#### 目标
- 强化项目结构：将导航路由与书架 UI 状态枚举从庞大的单文件中剥离，提升可维护性。
- 完善书架浏览状态记忆：将搜索关键词与搜索框展开状态一并持久化，并增加一键清除全部筛选条件的能力。

#### 实现
- 新增 [navigation/Screen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/navigation/Screen.kt)
  - 将原定义在 [MangaReaderApp.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/MangaReaderApp.kt) 中的 `Screen` 密封类迁移至此，避免入口文件同时承担导航图与路由定义职责。
- 新增 [bookshelf/BookshelfUiState.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/bookshelf/BookshelfUiState.kt)
  - 将 `BookshelfFilter`、`BookshelfSort`、`BookshelfViewMode` 三个枚举及其 `Saver` 从 [BookshelfScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/bookshelf/BookshelfScreen.kt) 中提取出来，供书架模块复用并减少主文件体积。
- [SettingsRepository.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/data/SettingsRepository.kt#L130)
  - 新增 `KEY_BOOKSHELF_SEARCH_QUERY` 与 `KEY_BOOKSHELF_SEARCHING`。
  - 新增 `getBookshelfSearchQuery()` / `saveBookshelfSearchQuery(query: String)`，空查询时删除键值。
  - 新增 `isBookshelfSearching()` / `saveBookshelfSearching(searching: Boolean)`。
- [BookshelfScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/bookshelf/BookshelfScreen.kt#L128)
  - 启动时恢复搜索关键词与搜索框状态；变更时写入 DataStore。
  - 在筛选行新增「清除条件」Chip：当存在任意筛选/排序/分类/搜索条件时显示，点击后一键重置所有书架浏览状态。

#### 行为
- 应用入口文件更专注于导航图组装，路由定义独立且语义清晰。
- 书架主文件不再包含枚举与 Saver 细节，结构更聚焦 UI 编排。
- 用户输入的搜索词、展开的搜索框在应用重启后可恢复。
- 当存在筛选/排序/分类/搜索条件时，筛选行左侧出现「清除条件」入口，一键恢复默认浏览状态。

#### 版本
- `versionCode` 53，`versionName` 1.0.53。

#### 测试
- `./gradlew assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：`No errors or warnings`。
- 单元测试 26 个全部通过。

---

### 13.64 拆分 BookshelfScreen：提取漫画条目与空状态组件 (2026-07-24)

#### 目标
继续降低 [BookshelfScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/bookshelf/BookshelfScreen.kt) 的文件复杂度，将 UI 细节（漫画列表/网格条目、角标、默认封面、空状态）提取到独立的 `bookshelf/components` 包中，使主屏幕更专注于状态编排与交互流程。

#### 实现
- 新增 [bookshelf/components/BookshelfComicItem.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/bookshelf/components/BookshelfComicItem.kt)
  - 从 BookshelfScreen 提取 `ComicCallbacks`、`ComicListItem`、`ComicCard`、`NewBadge`、`DefaultCoverIcon`。
  - 使用 `internal` 可见性控制组件作用域，避免对外泄露。
  - 添加 `@file:OptIn(ExperimentalFoundationApi::class)` 以满足 `combinedClickable` 的实验性 API 要求。
- 新增 [bookshelf/components/BookshelfEmptyStates.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/bookshelf/components/BookshelfEmptyStates.kt)
  - 从 BookshelfScreen 提取 `EmptyBookshelfState` 与 `EmptyBookshelf`。
- [BookshelfScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/bookshelf/BookshelfScreen.kt)
  - 删除上述私有组件，改为引用 `com.mangareader.bookshelf.components` 包中的对应实现。
  - 清理因此不再使用的导入（`combinedClickable`、`Color` 等）。
- [MangaReaderApp.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/MangaReaderApp.kt)
  - 沿用上一版本提取的 [navigation/Screen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/navigation/Screen.kt)，入口文件保持轻量。

#### 行为
- 功能与视觉表现完全一致，仅代码组织发生变化。
- BookshelfScreen 主文件行数显著下降，职责更单一。
- 漫画条目与空状态组件可在书架模块内独立维护与复用。

#### 版本
- `versionCode` 54，`versionName` 1.0.54。

#### 测试
- `./gradlew assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：`No errors or warnings`。
- 单元测试 26 个全部通过。

---

### 13.65 可访问性增强：阅读器自定义操作与书架卡片菜单操作 (2026-07-24)

#### 目标
提升屏幕阅读器（TalkBack）用户的操作效率：在阅读器页面直接暴露翻页与菜单入口；在书架网格卡片上暴露长按菜单入口，避免无障碍用户无法触发仅依赖手势的功能。

#### 实现
- [ReaderScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/reader/ReaderScreen.kt#L1057)
  - 为 [PageViewer](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/reader/ReaderScreen.kt#L748) 的根容器添加 `Modifier.semantics { customActions = ... }`。
  - 注册三项自定义无障碍操作：「上一页」「下一页」「打开菜单」，分别调用 `realOnTapPrev()`、`realOnTapNext()`、`onTapMenu()`。
  - 导入 `CustomAccessibilityAction`、`customActions`、`semantics`。
- [bookshelf/components/BookshelfComicItem.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/bookshelf/components/BookshelfComicItem.kt#L213)
  - 在 `ComicCard` 的 Column 上添加 `Modifier.semantics { customActions = ... }`。
  - 注册「更多选项」自定义操作，触发网格卡片的长按弹出菜单。
- [strings.xml](file:///d:/manhua2222/app/src/main/res/values/strings.xml#L733)
  - 新增 `reader_open_menu` 字符串资源「打开菜单」。

#### 行为
- TalkBack 聚焦阅读器页面时，可通过本地上下文菜单执行上一页/下一页/打开菜单，无需依赖不可见的点击热区。
- TalkBack 聚焦书架网格卡片时，可直接执行「更多选项」操作，打开收藏/分类/元数据/清除缓存等菜单。
- 原有手势、视觉表现与普通点击行为完全不变。

#### 版本
- `versionCode` 55，`versionName` 1.0.55。

#### 测试
- `./gradlew assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：`No errors or warnings`。
- 单元测试 26 个全部通过。

---

### 13.66 缓存与资源关闭审查：DiskLruCache 关闭后防写入加固 (2026-07-24)

#### 目标
针对缓存、协程作用域、资源关闭做细粒度审查，修复磁盘缓存在关闭后仍可能提交写入/调度 trim 的边界问题，避免 RejectedExecutionException 或文件写入异常。

#### 实现
- [DiskLruCache.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/cache/DiskLruCache.kt)
  - 在 `put()` 入口增加 `if (isShutdown) return null` 判断，关闭后拒绝新的写入任务。
  - 在 `scheduleTrim()` 入口增加 `if (isShutdown) return` 判断，关闭后不再向已终止的 `trimExecutor` 提交任务。
- 其余审查结果
  - `CbzParser` / `EpubParser` / `ComicBookParser` 已使用 `use`、读写锁、try/finally 保证流与锁释放。
  - `CoverExtractor` 各格式分支均已在 finally 或使用 `use` 关闭底层流与 `ParcelFileDescriptor`。
  - `PageCacheManager` 与 `ReaderViewModel` 已在 `onCleared` / `Application.onTerminate` 中正确取消协程并 `shutdown()`。
  - `SettingsRepository.saveAsync/flushSave` 的 Mutex + repositoryScope 机制已能有效避免旧快照覆盖与 UI 生命周期丢失写入。

#### 行为
- `DiskLruCache.shutdown()` 后再调用 `put()` 会安全返回 null，不会触发向已关闭线程池提交任务。
- 应用在进程终止或缓存清理场景下更稳定。

#### 版本
- `versionCode` 56，`versionName` 1.0.56。

#### 测试
- `./gradlew assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：`No errors or warnings`。
- 单元测试 26 个全部通过。

---

### 13.67 书架搜索历史功能 (2026-07-24)

#### 目标
在书架搜索框中记录最近使用的搜索关键词，方便用户快速回填与清空，提升大书架查找效率。

#### 实现
- [SettingsRepository.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/data/SettingsRepository.kt)
  - 新增 `KEY_BOOKSHELF_SEARCH_HISTORY` 与 `MAX_SEARCH_HISTORY_SIZE = 20`。
  - 新增 `getBookshelfSearchHistory()` / `addBookshelfSearchHistory()` / `clearBookshelfSearchHistory()`，使用 JSON 序列化存储，去重并将最新记录置顶。
- [BookshelfScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/bookshelf/BookshelfScreen.kt)
  - 搜索框增加 `ImeAction.Search` 键盘动作，提交时隐藏软键盘并把非空关键词写入历史。
  - 搜索框为空且存在历史时，显示清空历史按钮。
  - 搜索栏下方新增 `SearchHistoryPanel` 入口，仅在搜索展开、关键词为空、历史非空时显示。
- 新增 [BookshelfSearchHistory.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/bookshelf/components/BookshelfSearchHistory.kt)
  - 以 `FlowRow` + `SuggestionChip` 展示历史关键词，支持点击回填并刷新历史顺序。
  - 提供「清空历史」按钮一键移除全部记录。
- [strings.xml](file:///d:/manhua2222/app/src/main/res/values/strings.xml)
  - 新增 `bookshelf_search_history`、`bookshelf_search_history_clear`、`bookshelf_search_history_empty` 文案。

#### 行为
- 空/纯空白搜索不会被记录。
- 重复关键词会自动置顶。
- 历史记录上限 20 条，超出时移除最旧记录。

#### 版本
- `versionCode` 57，`versionName` 1.0.57。

#### 测试
- `./gradlew assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：`No errors or warnings`。
- 单元测试 26 个全部通过。

---

### 13.68 阅读器双击缩放动画与点击位置聚焦 (2026-07-24)

#### 目标
强化阅读器双击缩放体验：缩放过程带动画，并以用户点击的位置为中心进行聚焦，放大/复位更符合直觉。

#### 实现
- [ReaderScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/reader/ReaderScreen.kt)
  - 在 `ReaderScreen` 中新增 `readerScope` 与 `animatedZoomTo` 动画辅助函数，使用 `animate()` + `tween(180)` 对 `scale` 与 `panOffset` 做补间动画。
  - `PageViewer` 的 `onDoubleTapZoom` 回调改为 `onZoomTo: (Float, Offset) -> Unit`，由外层统一驱动动画。
  - 在 `PageViewer` 的点击手势处理中新增 `toggleZoom(tap)`：
    - 当前缩放大于 1.1 时复位到 `scale = 1f` / `offset = Zero`。
    - 否则放大到 `scale = 2.5f`，并根据点击位置、当前缩放/偏移计算目标 `panOffset`，使点击位置保持在屏幕同一位置。
    - 对目标偏移做边界钳制，避免放大后露出过多黑边。
  - 统一在双击、长按、单点三种手势的 `ZOOM_TOGGLE` 分支调用 `toggleZoom`，保持行为一致。

#### 行为
- 开启「启用缩放」后，双击页面以点击点为中心平滑放大；再次双击复位。
- 缩放动画时长 180ms，自然过渡。

#### 版本
- `versionCode` 58，`versionName` 1.0.58。

#### 测试
- `./gradlew assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：`No errors or warnings`。
- 单元测试 26 个全部通过。

---

### 13.69 书架批量分享漫画源文件 (2026-07-24)

#### 目标
支持在书架批量选择模式下，将已选漫画源文件通过系统分享一次性发送给其他应用，提升文件导出/备份效率。

#### 实现
- [BookshelfViewModel.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/bookshelf/BookshelfViewModel.kt)
  - 新增 `prepareShareUris(entries: List<ComicEntry>)`：
    - 仅处理文件类条目（跳过目录），超过 100MB 的文件会被跳过。
    - 将文件或 content URI 统一复制到 `source_share` 缓存目录（复制前清理旧缓存），文件名做路径净化并加时间戳/序号保证唯一。
    - 通过 `FileProvider` 返回可分享的 `content://` URI 列表。
- [BookshelfScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/bookshelf/BookshelfScreen.kt)
  - 批量操作栏新增「分享」按钮；选中条目后点击即可触发复制与分享。
  - 批量操作栏整体增加 `horizontalScroll`，避免按钮过多在小屏上被截断。
  - 新增 `shareComicFiles()` 辅助函数，使用 `ACTION_SEND_MULTIPLE` + `FLAG_GRANT_READ_URI_PERMISSION` 弹出分享选择器。
- [strings.xml](file:///d:/manhua2222/app/src/main/res/values/strings.xml)
  - 新增分享相关文案：`bookshelf_share_label`、`bookshelf_share_chooser`、`bookshelf_share_failed`、`bookshelf_share_empty`、`bookshelf_share_skipped_snackbar`。

#### 行为
- 选择文件类漫画后点击分享，系统弹出分享选择器。
- 目录条目不参与分享，完成后以 Snackbar 提示跳过数量。
- 缓存目录中的分享副本在下次分享前会被清理。

#### 版本
- `versionCode` 59，`versionName` 1.0.59。

#### 测试
- `./gradlew assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：1 个历史遗留 `ModifierParameter` 警告（位于 `BookshelfComicItem.kt`），本次无新增错误/警告。
- 单元测试 26 个全部通过。

### 13.70 UI 一致性优化：统一圆角与主题色 (2026-07-24)

#### 目标
在前序「书架顶栏简化」「阅读器底栏简化」基础上，进一步统一全书架的圆角规格，并将阅读器/章节浏览器中剩余的硬编码覆盖层颜色改为 theme-derived，确保暗色主题下各界面视觉一致、不突兀。

#### 实现
- [BookshelfScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/bookshelf/BookshelfScreen.kt)
  - 「最近阅读」卡片封面圆角从 `10.dp` 统一为 `12.dp`。
- [BookshelfComicItem.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/bookshelf/components/BookshelfComicItem.kt)
  - `NewBadge` 圆角从 `4.dp` 统一为 `12.dp`，形成胶囊式角标。
- [ChapterBrowserScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/chapters/ChapterBrowserScreen.kt)
  - 章节卡片页数角标圆角从 `4.dp` 统一为 `12.dp`。
  - 角标背景由硬编码 `Color.Black.copy(alpha = 0.65f)` 改为 `MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f)`，文字由硬编码 `Color.White` 改为 `MaterialTheme.colorScheme.onSurface`。
- [ReaderScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/reader/ReaderScreen.kt)
  - 书签列表缩略图圆角从 `6.dp` 统一为 `12.dp`。
  - 底部 scrubber 拖动预览卡片圆角从 `8.dp` 统一为 `12.dp`。
  - 底部缩略图条单项圆角从 `4.dp` 统一为 `12.dp`。
  - 分镜进度指示器背景由硬编码 `Color(0xAA000000)` 改为 `readerOverlayBackground()`，文字由 `Color.White` 改为 `readerOverlayContentColor()`。
  - 放大镜边框由 `Color(0x66FFFFFF)` 改为语义更清晰的 `Color.White.copy(alpha = 0.4f)`。
  - 修复两处 `MinimalBottomBar` 调用仍传入已删除的 `backgroundColor` 参数导致的编译错误，并移除因此闲置的 `panelColor` 变量。

#### 设计原则
- 继续遵循「内容优先，UI 隐形」：仅对控件容器做圆角/颜色收敛，不增加新的视觉层级。
- 圆角统一在 `12.dp` 这一 Material 中号规格，与项目记忆约束一致。
- 覆盖层颜色全部走 `readerOverlayBackground()` / `readerOverlayContentColor()` 等主题辅助函数，保证日间/夜间/自定义主题下均能自然融合。

#### 版本
- `versionCode` 60，`versionName` 1.0.60。

#### 测试
- `./gradlew assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：无新增错误/警告。
- 单元测试 26 个全部通过。

### 13.71 全面审查后的修复与体验增强 (2026-07-24)

#### 背景
基于 Brooks-Lint 架构审计（30/100）、技术债务评估（42/100）与 UX/Android 设计审查，选取 4 项高优先级、低风险的 Bug 修复与体验增强落地：阅读器状态颜色适配、退出确认「不再询问」、书架/分卷错误态重试、分卷浏览继续阅读入口。

#### 修复与增强
- [ReaderScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/reader/ReaderScreen.kt)
  - 修复阅读器空/加载/错误状态硬编码 `Color.White`，改为 `MaterialTheme.colorScheme.onSurface`，避免自定义浅色阅读背景时文字不可读。
  - 退出确认对话框新增「不再询问，直接退出」复选框；勾选后通过 `SettingsRepository.setReaderExitConfirmDisabled(true)` 持久化，下次返回直接退出。
  - 使用 `rememberUpdatedState` 确保 `BackHandler` 中的退出确认开关读取最新值。
- [SettingsRepository.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/data/SettingsRepository.kt)
  - 新增 `KEY_READER_EXIT_CONFIRM_DISABLED` 与 `getReaderExitConfirmDisabled()` / `setReaderExitConfirmDisabled()`。
- [BookshelfScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/bookshelf/BookshelfScreen.kt)
  - 书架加载错误状态由单行文本升级为带「重试」按钮的错误面板，点击调用 `viewModel.refresh()`。
- [ChapterBrowserScreen.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/chapters/ChapterBrowserScreen.kt) / [ChapterBrowserViewModel.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/chapters/ChapterBrowserViewModel.kt)
  - `ChapterBrowserViewModel` 在加载分卷后读取漫画阅读进度，当进度大于 0 时暴露 `continueAvailable = true`。
  - 分卷浏览 TopAppBar 在有进度时显示「继续阅读」TextButton，点击后不指定具体分卷、直接打开阅读器，由阅读器按保存页码自动续读。
  - 分卷浏览错误状态同样增加「重试」按钮。
- [strings.xml](file:///d:/manhua2222/app/src/main/res/values/strings.xml)
  - 新增 `reader_exit_dont_ask_again`、`bookshelf_error_retry`、`chapter_browser_continue_reading`。

#### 设计原则
- 「内容优先，UI 隐形」：退出确认与错误重试均为用户主动触发的反馈，不常驻干扰阅读。
- 状态颜色主题化：所有阅读器状态提示统一走 `MaterialTheme.colorScheme`，保证自定义背景与日间/夜间主题下可读。
- 减少重复操作：一次勾选即可消除高频返回的确认打断；一次点击即可从分卷页续读。

#### 版本
- `versionCode` 61，`versionName` 1.0.61。

#### 测试
- `./gradlew assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：无新增错误/警告。
- 单元测试 26 个全部通过。

### 13.72 架构解耦：彻底打破 data↔provider 与 parser↔utils 循环依赖 (2026-07-24)

#### 背景
Brooks-Lint 架构审计指出项目存在 package-level 循环依赖：
- `data` ↔ `provider`：`data.Models` 直接依赖 `provider.ComicProvider`，而 `provider` 实现又依赖 `data` 模型。
- `parser` ↔ `utils`：`utils.CoverExtractor` 依赖 `parser.ParserBitmapUtils` / `ParserFactory`，而 parser 内部又依赖 `utils.BitmapPool` / `FileManager` / `FileListHelper`。

#### 解耦方案
1. **接口上提打破 data↔provider**
   - 将 `ComicProvider` 接口从 `provider` 包迁移到 `data` 包（`data/ComicProvider.kt`）。
   - `provider` 各实现（`ParserBasedComicProvider`、`LocalFolderProvider`、`PluginComicProvider`、`NetworkComicProvider`、`ComicProviderFactory`）统一改为 `import com.mangareader.data.ComicProvider`。
   - `data.Models` 移除对 `provider` 的反向引用。

2. **工具上提打破 parser↔utils**
   - 新建 `utils/BitmapDecoderUtils.kt`，将原 `parser/ParserBitmapUtils.kt` 中的：
     - `ParserBitmapUtils`（含 `calculateInSampleSize`、`decodeSampled`、`getScreenSize`）
     - 扩展函数 `InputStream.readBytesLimited()`
     - 内部类 `ByteArrayOutputStreamLimited`
     全部上提到 `utils` 包，并改名为 `BitmapDecoderUtils`。
   - 新建 `utils/FileTypeUtils.kt`，将图片后缀判断逻辑 `isImageFile()` 上提到 `utils`。
   - `ParserFactory.isImageFile()` 改为委托 `com.mangareader.utils.isImageFile()`，保持现有调用方与测试兼容。
   - 删除 `parser/ParserBitmapUtils.kt`。
   - 更新 `CbzParser`、`CbrParser`、`Cb7Parser`、`CbtParser`、`EpubParser`、`ParserBasedComicProvider`、`CoverExtractor` 的导入与调用，统一指向 `utils.BitmapDecoderUtils`、`utils.readBytesLimited`、`utils.isImageFile`。

3. **修复迁移过程中暴露的编译错误**
   - `ReaderViewModel`：补充 `java.io.FileOutputStream` 导入；新增 `currentComicEntry` 字段并在 `loadComic()` 中赋值，修复 `shareSourceFile()` 引用的缺失属性。
   - `BookshelfViewModel`：补充 `FileManager` 与 `java.io.File` 导入；修复类末尾缺失的闭合大括号 `}`。

#### 依赖方向
- `data` 定义接口，`provider` / `parser` 依赖 `data` 与 `utils`。
- `utils` 仅依赖 `data` 模型与系统/Android API，不再反向依赖 `parser`。
- 形成清晰的单向依赖：`provider/parser` → `utils` → `data`。

#### 相关文件
- 新增：
  - `app/src/main/kotlin/com/mangareader/utils/BitmapDecoderUtils.kt`
  - `app/src/main/kotlin/com/mangareader/utils/FileTypeUtils.kt`
  - `app/src/main/kotlin/com/mangareader/data/ComicProvider.kt`
- 删除：
  - `app/src/main/kotlin/com/mangareader/parser/ParserBitmapUtils.kt`
  - `app/src/main/kotlin/com/mangareader/provider/ComicProvider.kt`
- 修改：
  - `app/src/main/kotlin/com/mangareader/parser/CbzParser.kt`
  - `app/src/main/kotlin/com/mangareader/parser/CbrParser.kt`
  - `app/src/main/kotlin/com/mangareader/parser/Cb7Parser.kt`
  - `app/src/main/kotlin/com/mangareader/parser/CbtParser.kt`
  - `app/src/main/kotlin/com/mangareader/parser/EpubParser.kt`
  - `app/src/main/kotlin/com/mangareader/parser/ParserFactory.kt`
  - `app/src/main/kotlin/com/mangareader/provider/ParserBasedComicProvider.kt`
  - `app/src/main/kotlin/com/mangareader/provider/LocalFolderProvider.kt`
  - `app/src/main/kotlin/com/mangareader/provider/PluginComicProvider.kt`
  - `app/src/main/kotlin/com/mangareader/provider/NetworkComicProvider.kt`
  - `app/src/main/kotlin/com/mangareader/provider/ComicProviderFactory.kt`
  - `app/src/main/kotlin/com/mangareader/utils/CoverExtractor.kt`
  - `app/src/main/kotlin/com/mangareader/reader/ReaderViewModel.kt`
  - `app/src/main/kotlin/com/mangareader/bookshelf/BookshelfViewModel.kt`
  - `app/src/main/kotlin/com/mangareader/data/Models.kt`
  - `app/src/main/kotlin/com/mangareader/cache/PageCacheManager.kt`
  - `app/build.gradle.kts`
  - `CODE_WIKI.md`

#### 版本
- `versionCode` 62，`versionName` 1.0.62。

#### 测试
- `./gradlew assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：无新增错误/警告。
- 单元测试 26 个全部通过。

---

### 13.74 ReaderViewModel 神类拆分：阅读统计与书签管理独立 (2026-07-24)

#### 背景
Brooks-Lint 健康扫描指出 `ReaderViewModel` 承担超过 15 项职责（阅读状态、TTS、缓存、书签、阅读统计、分享、自动翻页等），属于典型的 "god class"，导致认知负荷高、测试困难、迭代风险大。本轮先从最独立的两块——**阅读统计**与**书签管理**——切入，将其拆分为独立协作者类。

#### 拆分方案
1. **提取 `ReadingStatsTracker`**
   - 新建 `reader/ReadingStatsTracker.kt`。
   - 负责：阅读会话开始/结束、翻页页数统计、每日目标达成检测与提示。
   - 内部使用 `AtomicBoolean` 保证 `endSession()` 的线程安全，避免重复结算。
   - 会话结束与目标检测通过 `MangaReaderApplication.applicationScope` 在后台执行，防止 `ViewModel` 销毁导致保存丢失。

2. **提取 `BookmarkManager`**
   - 新建 `reader/BookmarkManager.kt`。
   - 负责：当前漫画书签列表的内存维护、与 `SettingsRepository` 的持久化同步、退出时的自动恢复书签创建。
   - 使用 `StateFlow` 暴露 `bookmarks`，让 `ReaderScreen` 自动订阅，无需 `ReaderViewModel` 手动转发。
   - `setComicUri()` 切换漫画时取消旧订阅，防止旧漫画书签更新污染当前状态。
   - 写入操作通过 `enqueueWrite()` 串行化，并捕获异常避免单次数写入失败影响阅读。

3. **精简 `ReaderViewModel`**
   - 删除原 `_bookmarks`、`bookmarkJob`、旧 `toggleBookmarkAt()` / `removeBookmark()` / `autoSaveBookmarkOnExit()` 方法。
   - `toggleBookmark()` 与 `removeBookmark()` 直接委托给 `BookmarkManager`。
   - `onCleared()` 中通过 `bookmarkManager.autoSaveBookmarkOnExit()` + `bookmarkManager.persist()` 完成退出保存。
   - 阅读统计相关调用改为 `statsTracker.startSession()`、`statsTracker.recordProgress()`、`statsTracker.endSession()`。

#### 相关文件
- 新增：
  - `app/src/main/kotlin/com/mangareader/reader/ReadingStatsTracker.kt`
  - `app/src/main/kotlin/com/mangareader/reader/BookmarkManager.kt`
- 修改：
  - `app/src/main/kotlin/com/mangareader/reader/ReaderViewModel.kt`
  - `app/build.gradle.kts`
  - `CODE_WIKI.md`

#### 版本
- `versionCode` 64，`versionName` 1.0.64。

#### 测试
- `.\gradlew.bat assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：无新增错误/警告。
- 单元测试 27 个全部通过。

### 13.75 SettingsRepository 神类拆分：提取 ReadingStatsRepository (2026-07-24)

#### 背景
Brooks-Lint 健康扫描指出 `SettingsRepository` 已经膨胀到 80+ 方法，既承担阅读设置、进度、书架、分类、备份，又负责阅读统计、目标、会话与目标历史。这种「万能仓库」导致任何统计改动都会牵动备份/恢复、目标界面、阅读器等大量调用点。本轮将**阅读统计、目标、会话、目标达成历史**独立为专属 Repository。

#### 拆分方案
1. **新建 `ReadingStatsRepository`**
   - 文件：`app/src/main/kotlin/com/mangareader/data/ReadingStatsRepository.kt`。
   - 职责：阅读统计的读取/更新、每日目标、阅读会话开始/结束/恢复、目标达成历史记录。
   - 使用单例 `getInstance()`，与 `SettingsRepository` 共享同一 DataStore。
   - 提供 `mergeReadingStats(backupStats, overwrite)` 供备份恢复时合并数据，避免在 `SettingsRepository.importAllData` 的主 edit 事务内嵌套 edit。

2. **新建 `DataStoreProvider`**
   - 文件：`app/src/main/kotlin/com/mangareader/data/DataStoreProvider.kt`。
   - 提供全局唯一的 `Context.userDataStore` 扩展属性，避免各 Repository 重复定义 `preferencesDataStore(name = "manga_reader_settings")`。

3. **`SettingsRepository` 改为 Facade**
   - 移除 `KEY_READING_STATS`、`KEY_READING_GOALS` 及相关解析/更新方法。
   - 保留阅读统计相关公开 API，通过 `private val statsRepository` 委托给 `ReadingStatsRepository`。
   - 导入修复：将 `androidx.datastore.core.Preferences` 修正为 `androidx.datastore.preferences.core.Preferences`，与 DataStore 实际包名一致。
   - 备份导入时阅读统计合并改为在 `importAllData` 主 edit 事务结束后调用 `statsRepository.mergeReadingStats(...)`。

4. **`ReadingStatsTracker` 与 `ReaderViewModel` 适配**
   - `ReadingStatsTracker` 构造函数由 `SettingsRepository` 改为 `ReadingStatsRepository`。
   - `ReaderViewModel` 初始化 `ReadingStatsTracker` 时传入 `ReadingStatsRepository.getInstance(application)`。

#### 相关文件
- 新增：
  - `app/src/main/kotlin/com/mangareader/data/ReadingStatsRepository.kt`
  - `app/src/main/kotlin/com/mangareader/data/DataStoreProvider.kt`
- 修改：
  - `app/src/main/kotlin/com/mangareader/data/SettingsRepository.kt`
  - `app/src/main/kotlin/com/mangareader/reader/ReadingStatsTracker.kt`
  - `app/src/main/kotlin/com/mangareader/reader/ReaderViewModel.kt`
  - `app/build.gradle.kts`
  - `CODE_WIKI.md`

#### 版本
- `versionCode` 66，`versionName` 1.0.66。

#### 测试
- `.\gradlew.bat assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：无新增错误/警告。
- 单元测试 27 个全部通过。

### 13.76 ReaderScreen UI 拆分：提取顶部栏/底部菜单/手势层/页面内容 (2026-07-24)

#### 背景
`ReaderScreen.kt` 单文件已膨胀到约 2900 行，Brooks-Lint 健康扫描指出阅读器层存在「超大 Composable」风险，单个函数同时承担状态聚合、系统栏/方向管理、对话框、页面渲染、手势处理、菜单绘制与缩略图条，导致阅读器 UI 难以维护。本轮在**不改动业务逻辑与 ViewModel 调用**的前提下，将 UI 区域拆分为独立 Composable，降低单函数复杂度。

#### 拆分方案
1. **保留 `ReaderScreen` 作为入口与状态聚合**
   - 文件：`app/src/main/kotlin/com/mangareader/reader/ReaderScreen.kt`。
   - 负责：收集 `ReaderUiState`/`PanelViewState`/菜单/书签/TTS 状态、屏幕尺寸通知、卷末/退出对话框、系统栏与方向/常亮管理、音量键处理、自动亮度、缩放动画状态。
   - 将页面渲染委托给新的 `ReaderPageContent`。

2. **新建 `ReaderScreenComponents.kt` 统一存放 UI 组件**
   - 文件：`app/src/main/kotlin/com/mangareader/reader/ReaderScreenComponents.kt`。
   - 提取组件：
     - `ReaderTopBar`：顶部标题栏（返回、标题、页码、设置）。
     - `ReaderBottomMenu`：底部菜单（章节跳转、进度滑条、书签、分享、TTS、更多）。
     - `ReaderGestureLayer`：手势处理层（点击热区、双击缩放、长按、滑动翻页、双指缩放）。
     - `ReaderPageContent` / `ReaderPages`：页面渲染主体（分镜/单页/双页/滚动模式、动画、背景纹理、状态栏、缩略图条、边缘退出）。
   - 复用原有主题色函数 `readerOverlayBackground()` / `readerOverlayContentColor()` / `readerScrimColor()`，保持主题一致。
   - 保留 `PageViewer`、`PanelViewer`、`VerticalScrollViewer`、`HorizontalScrollViewer`、`PageImage`、`ThumbnailStrip` 等既有渲染实现，仅调整可见性/位置。

3. **行为保持**
   - 不修改 `ReaderViewModel` 调用与状态管理。
   - 保持热区点击、页面动画、无障碍自定义操作、放大镜、缩略图条、卷末提示等行为一致。
   - 清理 `ReaderScreen.kt` 中不再需要的 `gestureConfig`、`onAction` 等中间变量。

#### 相关文件
- 新增：
  - `app/src/main/kotlin/com/mangareader/reader/ReaderScreenComponents.kt`
- 修改：
  - `app/src/main/kotlin/com/mangareader/reader/ReaderScreen.kt`
  - `app/build.gradle.kts`
  - `CODE_WIKI.md`

#### 版本
- `versionCode` 68，`versionName` 1.0.68。

#### 测试
- `.\gradlew.bat assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：无新增错误/警告。
- 单元测试 27 个全部通过。
- `ReaderScreen.kt` 剩余约 475 行（从约 2900 行减少），`ReaderScreenComponents.kt` 约 2663 行。

---

### 13.77 崩溃修复：Recycled Bitmap 与 LruCache sizeOf 不一致 (2026-07-27)

#### 问题
用户反馈两个崩溃：
1. `java.lang.RuntimeException: Canvas: trying to use a recycled bitmap`：缓存在 `PageCacheManager` / `processedBitmapCache` 中的 Bitmap 被外部代码或 LruCache 驱逐后 recycle，Compose 绘制时仍引用该 Bitmap 导致崩溃。
2. `java.lang.IllegalStateException: PageCacheManager$thumbMemoryCache$1.sizeOf() is reporting inconsistent results`：Bitmap 被 recycle 后 `allocationByteCount` 返回 0，而 LruCache 要求 sizeOf 返回正值，导致 trimToSize/evictAll 时校验失败抛出异常。

#### 修复
- [PageCacheManager.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/cache/PageCacheManager.kt)
  - 新增 `safeBitmapSize(bmp)`：若 Bitmap 为 null 或已 recycle，返回安全值 1 字节；否则返回 `allocationByteCount.coerceAtLeast(1)`，彻底消除 sizeOf 返回 0 的可能。
  - 将 `getPage()` 拆分为内部 `getOrDecodePage()` 与公开 `getPage()`；公开方法始终返回 `Bitmap.createBitmap(raw)` 独立副本，确保缓存内 Bitmap 永远不被外部 recycle。
  - `getThumbnail()` 同样返回副本。
- [ReaderViewModel.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/reader/ReaderViewModel.kt)
  - 新增 `createBitmapCopySafe(src)`：安全创建 Bitmap 副本，处理 OOM 与 recycle 状态；`processedBitmapCache` 存入和取出时均走副本，防止外部修改/回收影响缓存。
  - 所有对外暴露的 Bitmap（`loadPageBitmap` 等）保证返回独立副本，遵循「谁使用谁 recycle」的契约。
- [PageCacheManager.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/cache/PageCacheManager.kt) 的 LruCache `sizeOf` 统一使用 `safeBitmapSize`，避免 recycle 后字节数变为 0。

#### 版本
- `versionCode` 69，`versionName` 1.0.69。

#### 测试
- `.\gradlew.bat assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Lint 报告：无错误/警告。
- 单元测试全部通过。

---

### 13.78 完善 MOBI/AZW3 解析器 (2026-07-27)

#### 目标
完善 MOBI 格式解析功能，使应用能够像 Kindle 那样阅读 MOBI/AZW/AZW3 格式的电子书和漫画。修复关键的图片索引映射 bug，增加深色主题文本渲染支持。

#### 关键修复：图片 recindex 映射
MOBI HTML 中 `<img recindex="NNN">` 的 NNN 是**全局 PDB record 索引**（从 0 开始计数，跨所有 record），而非「图片列表的索引」或「相对于 firstImageIndex 的偏移」。之前的实现错误地将 recindex 直接作为 `images[]` 列表下标使用，导致插图/封面无法正确加载甚至越界。

- [MobiParser.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/parser/MobiParser.kt)
  - `ImageRecordInfo` 新增 `recordIndex: Int` 字段，记录该图片对应的原始 PDB record 编号。
  - 两阶段图片扫描（Phase 1 magic bytes / Phase 2 firstImageIndex 回退）在创建 `ImageRecordInfo` 时填入正确的 recordIndex。
  - 页面构建阶段新增 `imageByRecordIndex: Map<Int, ImageRecordInfo>` 映射表，HTML 解析得到的 recindex 通过该映射查找图片。
  - `buildComicPages()` 使用 `imageTextMap[info.recordIndex]` 关联文本内容；`buildEbookPages()` 使用 `imageByRecordIndex[recIndex]` 查找插图。

#### 深色主题文本渲染
- [TextPageRenderer.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/parser/mobi/TextPageRenderer.kt)
  - 构造函数新增 `isDarkTheme: Boolean = false` 参数。
  - 深色主题配色遵循应用规范：背景 `#121212`、正文 `#E0E0E0`、页码 `#888888`；浅色主题使用经典白底黑字。
  - Bitmap 配置改为 `RGB_565`（文本页无需 Alpha 通道），减少内存占用。
  - 移除未使用的 Log 导入、totalPages 参数，页码显示简化为仅当前页号。
  - [MobiParser.kt](file:///d:/manhua2222/app/src/main/kotlin/com/mangareader/parser/MobiParser.kt) 在构建电子书页面时通过 `Configuration.UI_MODE_NIGHT_MASK` 检测系统深色模式，传入 TextPageRenderer。

#### 代码清理
- 移除未使用的 import（`BitmapFactory`、`Canvas`、`Color`、`Matrix`）。
- 移除 `buildPages()` 中未使用的 `rawHtml`、`header` 参数；`buildComicPages()` 中未使用的 `imageByRecordIndex` 参数。
- `detectImageInRecord()` / `detectImageInRecordExtended()` 移除未使用的 `recordSize` 参数（函数内仅读取 probe 缓冲区，无需 record 总长度）。
- 清理冗余注释。

#### MOBI 解析能力
现有解析器支持：
- **PDB 头部**解析：record 偏移表、属性。
- **MOBI 6 / KF8 (AZW3) 双格式**：EXTH 中检测 KF8 boundary，优先使用 KF8 内容。
- **PalmDOC LZ77 解压**：支持无压缩 (1) 和 PalmDOC 压缩 (2)，自动识别 codepage（UTF-8/CP1252/GBK/Shift_JIS/Big5/EUC-KR）。
- **DRM 检测**：加密类型非 0 时直接拒绝。
- **EXTH 元数据**：书名、作者、出版社、描述、封面/缩略图偏移、语言。
- **图片检测两阶段策略**：Phase 1 magic bytes 全量扫描（JPEG/PNG/GIF/BMP/WebP/TIFF/JP2）；Phase 2 firstImageIndex 回退，兼容非标准填充的图片。
- **智能模式判定**：文本量 ≥200 字符且文本页 ≥2 页且文本页多于图片页 → 电子书模式（文本渲染为 Bitmap，图片作为插图页）；否则 → 漫画模式（每张图片一页）。
- **文本渲染**：StaticLayout + TextPaint + Canvas，支持标题（h1-h3 居中加粗）、正文两端对齐、行距 1.5 倍、页码、CJK 字符、深色/浅色主题。

#### 相关文件
- 修改：
  - `app/src/main/kotlin/com/mangareader/parser/MobiParser.kt`
  - `app/src/main/kotlin/com/mangareader/parser/mobi/TextPageRenderer.kt`
  - `app/src/main/kotlin/com/mangareader/cache/PageCacheManager.kt`
  - `app/src/main/kotlin/com/mangareader/reader/ReaderViewModel.kt`
  - `app/build.gradle.kts`（版本号更新）
  - `CODE_WIKI.md`

#### 版本
- `versionCode` 70，`versionName` 1.0.70。

#### 测试
- `.\gradlew.bat assembleDebug testDebugUnitTest lintDebug` 全部通过。
- Kotlin 编译无警告。
- Lint 报告：无错误/警告。
- 单元测试全部通过。

### 13.79 架构治理：消除 ComicHolder 全局单例 (2026-07-27)

#### 背景
`ComicHolder`（原定义于 `data/Models.kt`）是一个全局可变单例，在 Bookshelf/Browser/ChapterBrowser/Reader 之间传递漫画数据。它导致多个 UI 屏隐式耦合，无法享受 Navigation 的类型安全参数、深层链接与进程恢复。

#### 重构方案
1. **移除 `object ComicHolder`**。
2. **Navigation 路由扩展**（`navigation/Screen.kt`）：
   - `ChapterBrowser` 路由改为 `chapter_browser/{comicUri}`。
   - `Reader` 路由改为 `reader/{comicUri}?chapterUri={chapterUri}`。
   - 使用 `Uri.encode/decode` 安全传递 URI。
3. **调用点适配**：
   - `MangaReaderApp.kt`：`openComic` 改为导航到 `Screen.ChapterBrowser.createRoute(entry.uri)`。
   - `ChapterBrowserScreen.kt`：接收 `comicUri: Uri`，内部重建 `ComicEntry`。
   - `ReaderScreen.kt`：接收 `comicUri/chapterUri`，通过 `viewModel.loadComicFromUri(...)` 加载。
   - `BookshelfScreen.kt` / `BrowserScreen.kt`：移除 `ComicHolder` 读写。
4. **行为保持**：点进漫画后仍打开对应分卷，卷末仍能跳转到下一卷。

#### 相关文件
- 修改：
  - `app/src/main/kotlin/com/mangareader/navigation/Screen.kt`
  - `app/src/main/kotlin/com/mangareader/data/Models.kt`
  - `app/src/main/kotlin/com/mangareader/MangaReaderApp.kt`
  - `app/src/main/kotlin/com/mangareader/chapters/ChapterBrowserScreen.kt`
  - `app/src/main/kotlin/com/mangareader/reader/ReaderScreen.kt`
  - `app/src/main/kotlin/com/mangareader/reader/ReaderScreenComponents.kt`
  - `app/src/main/kotlin/com/mangareader/bookshelf/BookshelfScreen.kt`
  - `app/src/main/kotlin/com/mangareader/browser/BrowserScreen.kt`
- 版本：`versionCode` 78，`versionName` 1.0.78。
- 测试：`.\gradlew.bat assembleDebug testDebugUnitTest lintDebug` 全部通过，73 个单元测试通过，Lint 无新增错误/警告。

### 13.80 ReaderViewModel 神类拆分：PageLoader/TtsController/AutoPager/ProgressSaver (2026-07-27)

#### 背景
`ReaderViewModel` 原约 1354 行，同时承担漫画加载、TTS、自动翻页、进度保存、图像后处理、分镜模式、分享、缓存管理等职责。本轮继续将其中的**加载、TTS、自动翻页、进度保存**提取为独立协作者。

#### 拆分方案
1. **新建 `PageLoader`**（`reader/PageLoader.kt`，约 114 行）
   - 负责漫画解析、`pages`/`parserRef`/`provider` 管理、`allChapters`/`currentChapterIndex`。
   - 暴露 `resolvePages()`、`releaseResources()`、`computeOverallProgress()`。
2. **新建 `TtsController`**（`reader/TtsController.kt`，约 100 行）
   - 封装 `TtsManager`，暴露 `speakCurrentPage()`、`stop()`、`shutdown()`。
   - 暴露 `isSpeaking: StateFlow<Boolean>` 供 Compose 收集。
3. **新建 `AutoPager`**（`reader/AutoPager.kt`，约 88 行）
   - 封装 `autoPageJob`、`startAutoPage()`、`stopAutoPage()`、`autoPageWasRunning`。
4. **新建 `ProgressSaver`**（`reader/ProgressSaver.kt`，约 96 行）
   - 封装进度防抖写入、`flipCount`、`lastRecordedPage`。
5. **ReaderViewModel 保留编排层**（约 1203 行）
   - 持有 `pageLoader`、`ttsController`、`autoPager`、`progressSaver`、`bookmarkManager`、`statsTracker`。
   - 保留 `_uiState`/`_showMenu`/`_panelState` 等状态流。
   - `ReaderUiState`/`PanelViewState` 改为嵌套类，匹配 `ReaderScreenComponents.kt` 既有引用。

#### 相关文件
- 新增：
  - `app/src/main/kotlin/com/mangareader/reader/PageLoader.kt`
  - `app/src/main/kotlin/com/mangareader/reader/TtsController.kt`
  - `app/src/main/kotlin/com/mangareader/reader/AutoPager.kt`
  - `app/src/main/kotlin/com/mangareader/reader/ProgressSaver.kt`
- 修改：
  - `app/src/main/kotlin/com/mangareader/reader/ReaderViewModel.kt`
  - `app/src/main/kotlin/com/mangareader/reader/ReaderScreen.kt`
  - `app/src/main/kotlin/com/mangareader/reader/ReaderScreenComponents.kt`
  - `app/build.gradle.kts`
  - `CODE_WIKI.md`
- 版本：`versionCode` 78，`versionName` 1.0.78。
- 测试：`.\gradlew.bat assembleDebug testDebugUnitTest lintDebug` 全部通过，73 个单元测试通过，Lint 无新增错误/警告。

### 13.81 SettingsRepository 二次拆分：五个单一职责 Repository (2026-07-28)

#### 背景
`SettingsRepository` 在首轮拆分后仍达 1627 行，继续混合书签、收藏、分类、历史、书单、书架、进度等职责。本轮将其彻底拆分为五个专属 Repository。

#### 拆分方案
1. **`BookmarkRepository.kt`**（210 行）：书签、收藏、分类、漫画分类
2. **`BookshelfRepository.kt`**（220 行）：书架根目录/URI/索引/视图模式/筛选/排序/搜索/扫描状态
3. **`ReadingHistoryRepository.kt`**（92 行）：阅读历史、最近阅读、已读/未读标记
4. **`ComicExtrasRepository.kt`**（84 行）：漫画评分/短评/标签、用户书单
5. **`ReadingProgressRepository.kt`**（184 行）：阅读进度、总页数

#### Facade 模式
- `SettingsRepository` 精简至 **971 行**（原 1627 行，减少 40%），保留所有公开方法签名作为 Facade，内部委托给新 Repository。
- 备份/恢复（`exportAllData`/`importAllData`）因跨 Repository 协调保留在 Facade 中。
- 所有 Repository 通过 `DataStoreProvider` 共享同一 DataStore，使用 `getInstance(context)` 单例。

#### 相关文件
- 新增：`data/BookmarkRepository.kt`、`data/BookshelfRepository.kt`、`data/ReadingHistoryRepository.kt`、`data/ComicExtrasRepository.kt`、`data/ReadingProgressRepository.kt`
- 修改：`data/SettingsRepository.kt`、`app/build.gradle.kts`、`CODE_WIKI.md`
- 版本：`versionCode` 80，`versionName` 1.0.80。
- 测试：`.\gradlew.bat assembleDebug testDebugUnitTest lintDebug` 全部通过。

### 13.82 BookshelfScreen UI 拆分：提取顶部栏/搜索栏/筛选栏/内容区/对话框 (2026-07-28)

#### 背景
`BookshelfScreen.kt` 原 1691 行，单 Composable 混合顶部栏、搜索、筛选、内容区、对话框等所有 UI 逻辑。

#### 拆分方案
1. **`BookshelfTopBar.kt`**（106 行）：顶部栏
2. **`BookshelfBatchToolbar.kt`**（78 行）：批量操作栏
3. **`BookshelfSearchBar.kt`**（98 行）：搜索栏 + 搜索历史面板
4. **`BookshelfFilterBar.kt`**（173 行）：筛选/排序栏 + 分类筛选行
5. **`BookshelfContent.kt`**（196 行）：内容区（下拉刷新 + 错误/空/网格/列表）
6. **`BookshelfDialogs.kt`**（526 行）：所有对话框

#### 复用已有组件
- `BookshelfComicItem.kt`、`BookshelfEmptyStates.kt`、`BookshelfSearchHistory.kt`、`BookshelfExtras.kt`、`GitHubStarDialog.kt`

#### 相关文件
- 新增：`bookshelf/BookshelfTopBar.kt`、`BookshelfBatchToolbar.kt`、`BookshelfSearchBar.kt`、`BookshelfFilterBar.kt`、`BookshelfContent.kt`、`BookshelfDialogs.kt`
- 修改：`bookshelf/BookshelfScreen.kt`、`app/build.gradle.kts`、`CODE_WIKI.md`
- 版本：`versionCode` 80，`versionName` 1.0.80。
- 测试：`.\gradlew.bat assembleDebug testDebugUnitTest lintDebug` 全部通过。

### 13.83 关键缺陷修复：漫画识别 / 缓存膨胀 / 退出白屏 (2026-10-11)

#### 修复内容
1. **漫画无法识别（双解码破坏 SAF URI）**
   - 现象：任何格式漫画点击后都提示无内容 / 无法识别。
   - 根因：`navArgument(...) { type = NavType.StringType }` 的路径参数已由 AndroidX Navigation 自动 URLDecode 一次；`MangaReaderApp.kt` 读取后又调用 `Uri.decode(...)` 二次解码，把 SAF documentId 中的 `%2F`/`%3A` 还原成 `/`、`:`，导致 `content://.../document/primary%3AComics%2FChapter1.cbz` 这类文档 URI 被截断破坏。
   - 修复：移除 ChapterBrowser / Reader 两处多余的 `Uri.decode`，仅保留一次 `Uri.parse`。

2. **缓存大量占用**
   - 分享卡片写入 `cacheDir` 根目录，而 `CacheCleaner.cleanShareCards` 只扫描 `cacheDir/share_cards/`，导致永不清理；改为写入 `share_cards/` 子目录，并让清理逻辑兼容扫描根目录的历史遗留文件。
   - `CoverExtractor.extractLocalImageThumbnail` 写 `thumb_local_*.png` 后从不调用 `cleanupOldCovers`，FOLDER 漫画缩略图无界增长；补齐按 `MAX_COVER_CACHE_FILES` 上限清理。
   - `CacheCleaner` 临时文件前缀补齐 `mobi_temp_` / `mobi_thumb_`。

3. **退出白屏**
   - 根因：`MainActivity` 的根 `Surface` 位于 `MangaReaderTheme` 之外，回退到 Material3 默认浅色 colorScheme（白）作为底层背景。
   - 修复：移除该未主题化 Surface，改由 `MangaReaderApp` 在 `MangaReaderTheme` 内使用主题化 `Surface(color = colorScheme.background)` 提供根背景；帮助 / 崩溃弹窗一并移入主题作用域。

#### 相关文件
- 修改：`MangaReaderApp.kt`、`MainActivity.kt`、`reader/ReaderViewModel.kt`、`utils/CoverExtractor.kt`、`cache/CacheCleaner.kt`、`app/build.gradle.kts`、`CODE_WIKI.md`
- 版本：`versionCode` 81，`versionName` 1.0.81。
- 验证：`.\gradlew.bat assembleDebug testDebugUnitTest lintDebug` 全部通过。

### 13.84 分卷浏览跳转链路修复 (2026-10-11)

#### 修复内容
1. **点击分卷进入冗余的二级列表、需多次点击才能阅读**
   - 根因：书架点击事件对目录类漫画走 `viewModel.openDirectory(comic.uri)`，即在书架内再压入一层文件夹列表；而项目硬约束（见 4.4 及 `openComic`）要求「点击漫画先进分卷浏览」。于是形成 书架 → 文件夹列表 → 分卷浏览 → 阅读器 的多级冗余路径。
   - 修复：`BookshelfScreen` 点击目录类漫画时改为 `onOpenComic(comic)`，直接进入分卷浏览，与 `continueReading` 行为统一。

2. **无法跳转到同一漫画的下一卷**
   - 根因：上一条导致用户实际是按「单个文件」打开每一卷，阅读器内 `allChapters` 只有一卷，`jumpToNextChapter()` 自然无下一卷可跳。
   - 修复：目录漫画经分卷浏览打开后，阅读器解析整本漫画（`ComicBookParser`），全部卷进入同一页面列表，卷末对话框与阅读器内「分卷列表」均可正常跨卷跳转。

3. **分卷列表无法正常显示**
   - 根因：阅读器内「分卷列表」中非当前卷的标题硬编码为 `Color.White`，浅色主题下与对话框底色同色导致不可见。
   - 修复：改用 `MaterialTheme.colorScheme.onSurface`。

#### 相关文件
- 修改：`bookshelf/BookshelfScreen.kt`、`reader/ReaderScreenComponents.kt`、`app/build.gradle.kts`、`CODE_WIKI.md`
- 版本：`versionCode` 82，`versionName` 1.0.82。
- 验证：`.\gradlew.bat assembleDebug testDebugUnitTest lintDebug` 全部通过。

*文档更新时间：2026-10-11*