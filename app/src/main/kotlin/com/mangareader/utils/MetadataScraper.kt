package com.mangareader.utils

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 智能元数据刮削器
 * 从文件名自动解析漫画信息，支持在线查询
 */
@Serializable
data class ComicMetadata(
    val title: String = "",
    val author: String = "",
    val coverUrl: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val volume: Int = 0,
    val chapter: String = "",
    val year: Int = 0,
    val source: String = "local"
)

object MetadataScraper {

    private val json = Json { ignoreUnknownKeys = true }

    // 常见文件名模式
    private val patterns = listOf(
        // [作者] 标题 第X卷/话
        Regex("""^\[(.+?)\]\s*(.+?)\s*[第Vol]\.?\s*(\d+)\s*[卷话冊話]"""),
        // 标题 第X卷 by 作者
        Regex("""^(.+?)\s*[第Vol]\.?\s*(\d+)\s*[卷话冊話].*[by\s\-]+(.+?)$"""),
        // (汉化组) [作者] 标题 Vol.X
        Regex("""^\(.+?\)\s*\[(.+?)\]\s*(.+?)\s*[Vv]ol\.?\s*(\d+)"""),
        // 标题 Vol.X
        Regex("""^(.+?)\s*[Vv]ol\.?\s*(\d+)"""),
        // 标题 第X话
        Regex("""^(.+?)\s*[第#](\d+)\s*[话話]"""),
        // 纯标题
        Regex("""^(.+?)(?:\s*[\[\(].*?[\]\)])?\s*$""")
    )

    /**
     * 从文件名解析元数据
     */
    fun parseFilename(filename: String): ComicMetadata {
        val name = filename.removeSuffix(".zip").removeSuffix(".cbz")
            .removeSuffix(".cbr").removeSuffix(".cb7").removeSuffix(".cbt")
            .removeSuffix(".epub").removeSuffix(".pdf")
            .trim()

        for (pattern in patterns) {
            val match = pattern.find(name) ?: continue
            val groups = match.groupValues.drop(1)

            return when (groups.size) {
                3 -> ComicMetadata(
                    author = if (isAuthorLike(groups[0])) groups[0] else "",
                    title = if (isAuthorLike(groups[0])) groups[1] else groups[0],
                    volume = groups[2].toIntOrNull() ?: 0,
                    chapter = if (name.contains("话")) groups[2] else ""
                )
                2 -> {
                    if (groups[1].toIntOrNull() != null) {
                        ComicMetadata(
                            title = groups[0],
                            volume = groups[1].toIntOrNull() ?: 0,
                            chapter = if (name.contains("话")) groups[1] else ""
                        )
                    } else {
                        ComicMetadata(title = groups[0], author = groups[1])
                    }
                }
                1 -> ComicMetadata(title = groups[0])
                else -> continue
            }
        }

        return ComicMetadata(title = name)
    }

    private fun isAuthorLike(text: String): Boolean {
        // 作者名通常较短，且包含特定字符
        return text.length <= 10 && !text.contains("卷") && !text.contains("话")
                && !text.contains("Vol") && !text.contains("vol")
    }

    /**
     * 从Bangumi API查询漫画元数据
     */
    suspend fun searchBangumi(keyword: String): ComicMetadata? {
        return try {
            val encoded = URLEncoder.encode(keyword, "UTF-8")
            val url = URL("https://api.bgm.tv/search/subject/$encoded?type=1&responseGroup=small")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "BestComic/1.0")
                connectTimeout = 5000
                readTimeout = 5000
            }

            if (conn.responseCode != 200) return null

            val body = conn.inputStream.bufferedReader().readText()
            val result = json.parseToJsonElement(body).jsonObject

            val list = result["list"]?.jsonArray ?: return null
            if (list.size == 0) return null

            val item = list[0].jsonObject
            ComicMetadata(
                title = item["name_cn"]?.jsonPrimitive?.content
                    ?: item["name"]?.jsonPrimitive?.content ?: keyword,
                author = "", // Bangumi simple search doesn't return author
                description = item["summary"]?.jsonPrimitive?.content ?: "",
                coverUrl = item["images"]?.jsonObject?.get("large")?.jsonPrimitive?.content ?: "",
                tags = emptyList(),
                source = "bangumi"
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 从MangaDex API查询 */
    suspend fun searchMangaDex(title: String): ComicMetadata? {
        return try {
            val encoded = URLEncoder.encode(title, "UTF-8")
            val url = URL("https://api.mangadex.org/manga?title=$encoded&limit=1&includes[]=cover_art")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "BestComic/1.0")
                connectTimeout = 5000
                readTimeout = 5000
            }

            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            val result = json.parseToJsonElement(body).jsonObject
            val data = result["data"]?.jsonArray ?: return null
            if (data.size == 0) return null

            val manga = data[0].jsonObject
            val attrs = manga["attributes"]?.jsonObject ?: return null
            val titleObj = attrs["title"]?.jsonObject
            val titleEn = titleObj?.get("en")?.jsonPrimitive?.content ?: title

            val tags = attrs["tags"]?.jsonArray?.mapNotNull { tag: kotlinx.serialization.json.JsonElement ->
                tag.jsonObject["attributes"]?.jsonObject?.get("name")?.jsonObject?.get("en")?.jsonPrimitive?.content
            } ?: emptyList()

            // Get cover URL
            var coverUrl = ""
            manga["relationships"]?.jsonArray?.let { rels ->
                for (rel in rels) {
                    if (rel.jsonObject["type"]?.jsonPrimitive?.content == "cover_art") {
                        val coverAttr = rel.jsonObject["attributes"]?.jsonObject
                        val fileName = coverAttr?.get("fileName")?.jsonPrimitive?.content ?: ""
                        val mangaId = manga["id"]?.jsonPrimitive?.content ?: ""
                        if (fileName.isNotEmpty()) {
                            coverUrl = "https://uploads.mangadex.org/covers/$mangaId/$fileName"
                        }
                    }
                }
            }

            ComicMetadata(
                title = titleEn,
                description = attrs["description"]?.jsonObject?.get("en")?.jsonPrimitive?.content ?: "",
                tags = tags,
                coverUrl = coverUrl,
                year = attrs["year"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                source = "mangadex"
            )
        } catch (e: Exception) {
            null
        }
    }
}