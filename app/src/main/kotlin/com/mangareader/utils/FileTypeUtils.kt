package com.mangareader.utils

/**
 * 判断文件名是否为常见图片格式。
 * 从 ParserFactory 上提到 utils，供 parser、CoverExtractor 等通用场景复用，
 * 避免 utils 反向依赖 parser。
 */
fun isImageFile(name: String): Boolean {
    val lower = name.lowercase()
    val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "bmp", "avif")
    return imageExtensions.any { lower.endsWith(".$it") }
}
