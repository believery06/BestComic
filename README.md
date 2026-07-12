# MangaReader - 本地漫画阅读器

一款基于 Android Jetpack Compose 的本地漫画阅读器，采用「内容优先，UI 隐形」的设计理念，支持多种漫画格式与沉浸式阅读体验。

> 本项目为个人/小团队作品，欢迎试用、提 Issue 或提交 PR。

## 主要特性

- **多格式支持**：CBZ/ZIP、CBR/RAR、CB7/7Z、CBT/TAR、EPUB、PDF、图片文件夹
- **书架管理**：以文件夹为单位管理漫画系列，子文件作为分卷/章节
- **分卷浏览**：进入漫画先展示分卷缩略图（Perfect Viewer 风格）
- **多种阅读模式**：单页、双页、垂直滚动、水平滚动，支持日漫 RTL
- **画面优化**：亮度/对比度/Gamma/饱和度、自动裁白边、灰度、锐化、降噪、镜像、旋转
- **大范围内存缓存**：阅读时自动预加载当前页前后各 15 页，退出阅读自动释放
- **手势热区**：左/右翻页、中央呼出菜单，热区大小可调
- **主题皮肤**：经典黑、牛皮纸、赛博朋克、护眼绿、酒红幕
- **阅读进度**：自动保存页码与书签
- **实验性功能**：SMB/NAS 流式阅读、WebDAV 同步（入口已预留，尚未完全实现）

## 截图

（此处可添加应用截图）

## 环境要求

- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17
- Android SDK 34（编译）/ 26（最低运行）

## 构建与运行

项目使用本地 Gradle Wrapper：

```bash
# 构建 debug APK
.\gradlew assembleDebug

# 安装到已连接设备
.\gradlew installDebug

# 清理构建缓存
.\gradlew clean
```

构建成功后 APK 位于：

```
app/build/outputs/apk/debug/app-debug.apk
```

## 使用说明

1. 首次打开后，点击右上角 **+** 选择一个本地文件夹作为书架目录。
2. 书架中的每个子文件夹会被视为一本漫画。
3. 点击漫画进入分卷浏览，选择分卷后开始阅读。
4. 阅读时点击屏幕中央唤出菜单，左右热区翻页。

更多细节请参考项目内的 [CODE_WIKI.md](./CODE_WIKI.md)。

## 已知限制

- **SMB/NAS 流式阅读**：当前仅支持浏览 HTTP 文件列表，还不能直接打开远程漫画。
- **WebDAV 同步**：可配置服务器并测试连接，但完整的数据上传/下载/自动同步尚未实现。

## 贡献

欢迎提交 Issue 和 Pull Request！

1. Fork 本仓库
2. 创建你的特性分支：`git checkout -b feature/AmazingFeature`
3. 提交改动：`git commit -m 'Add some AmazingFeature'`
4. 推送分支：`git push origin feature/AmazingFeature`
5. 发起 Pull Request

## 许可证

TODO: 请在此添加开源许可证（推荐 MIT 或 Apache-2.0）。
