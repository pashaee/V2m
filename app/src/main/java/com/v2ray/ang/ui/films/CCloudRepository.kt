package com.v2ray.ang.ui.films

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class CCloudGenre(
    val id: Int,
    val title: String
)

object CCloudRepository {

    private const val CCLOUD_API_KEY = "4F5A9C3D9A86FA54EACEDDD635185"

    private val CCLOUD_API_SERVERS = listOf(
        "https://server-hi-speed-iran.info",
        "https://hostinnegar.com"
    )

    // نگهداری ژانرهای دریافت‌شده از سرور به همراه ID واقعی آن‌ها برای فیلتر مستقیم از سرور
    private val discoveredGenres = ConcurrentHashMap<Int, String>()

    private val directClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun mapOrder(order: String): String {
        return when (order) {
            "rating", "imdb" -> "imdb"
            "year" -> "year"
            "views" -> "views"
            else -> "created"
        }
    }

    private fun fetchDirectUrl(urlStr: String): String? {
        try {
            val request = Request.Builder()
                .url(urlStr)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Accept", "*/*")
                .build()
            directClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank() && !body.trimStart().startsWith("<!DOCTYPE html", ignoreCase = true)) {
                        return body
                    }
                }
            }
        } catch (_: Exception) {
        }

        return try {
            val conn = (URL(urlStr).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
            }
            if (conn.responseCode in 200..299) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                if (text.isNotBlank() && !text.trimStart().startsWith("<!DOCTYPE html", ignoreCase = true)) text else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun resolvePlayableStreamUrl(rawUrl: String): String = withContext(Dispatchers.IO) {
        val sanitized = sanitizeStreamUrl(rawUrl)
        try {
            val request = Request.Builder()
                .url(sanitized)
                .head()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) Chrome/124.0.0.0 Mobile Safari/537.36")
                .build()
            directClient.newCall(request).execute().use { it.request.url.toString() }
        } catch (_: Exception) {
            sanitized
        }
    }

    /**
     * دریافت لیست کامل دسته‌بندی‌ها (ژانرها) با ID اصلی از سرور CCloud
     */
    suspend fun fetchAllGenres(): List<CCloudGenre> = withContext(Dispatchers.IO) {
        val paths = listOf(
            "/api/genre/all/$CCLOUD_API_KEY",
            "/api/genre/all/$CCLOUD_API_KEY/"
        )
        for (server in CCLOUD_API_SERVERS) {
            for (path in paths) {
                val raw = fetchDirectUrl("$server$path") ?: continue
                try {
                    val arr = JSONArray(raw.trim())
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val id = obj.optInt("id", 0)
                        val title = obj.optString("title").trim()
                        if (id > 0 && title.isNotEmpty()) {
                            discoveredGenres[id] = title
                        }
                    }
                    if (discoveredGenres.isNotEmpty()) break
                } catch (_: Exception) {
                }
            }
            if (discoveredGenres.isNotEmpty()) break
        }

        val sorted = discoveredGenres.entries
            .map { CCloudGenre(id = it.key, title = it.value) }
            .sortedBy { it.title }

        listOf(CCloudGenre(id = 0, title = "All")) + sorted
    }

    fun getCachedGenres(): List<CCloudGenre> {
        val sorted = discoveredGenres.entries
            .map { CCloudGenre(id = it.key, title = it.value) }
            .sortedBy { it.title }
        return listOf(CCloudGenre(id = 0, title = "All")) + sorted
    }

    /**
     * دریافت صفحه‌بندی‌شده فیلم‌ها بر اساس ژانر و ترتیب مستقیم از سرور
     */
    suspend fun fetchMoviesPage(
        genreId: Int = 0,
        order: String = "created",
        page: Int = 0
    ): List<CCloudMediaItem> = withContext(Dispatchers.IO) {
        val mappedOrder = mapOrder(order)
        val path = "/api/movie/by/filtres/$genreId/$mappedOrder/$page/$CCLOUD_API_KEY"
        for (server in CCLOUD_API_SERVERS) {
            val raw = fetchDirectUrl("$server$path")
            if (!raw.isNullOrBlank()) {
                val parsed = parsePostersArray(raw, isSeriesDefault = false)
                if (parsed.isNotEmpty()) return@withContext parsed
            }
        }
        emptyList()
    }

    /**
     * دریافت صفحه‌بندی‌شده سریال‌ها بر اساس ژانر و ترتیب مستقیم از سرور
     */
    suspend fun fetchSeriesPage(
        genreId: Int = 0,
        order: String = "created",
        page: Int = 0
    ): List<CCloudMediaItem> = withContext(Dispatchers.IO) {
        val mappedOrder = mapOrder(order)
        val path = "/api/serie/by/filtres/$genreId/$mappedOrder/$page/$CCLOUD_API_KEY"
        for (server in CCLOUD_API_SERVERS) {
            val raw = fetchDirectUrl("$server$path")
            if (!raw.isNullOrBlank()) {
                val parsed = parsePostersArray(raw, isSeriesDefault = true)
                if (parsed.isNotEmpty()) return@withContext parsed
            }
        }
        emptyList()
    }

    suspend fun fetchSeriesSeasons(serieId: Int): List<CCloudSeasonItem> = withContext(Dispatchers.IO) {
        val paths = listOf(
            "/api/season/by/serie/$serieId/$CCLOUD_API_KEY/",
            "/api/season/by/serie/$serieId/$CCLOUD_API_KEY"
        )
        for (server in CCLOUD_API_SERVERS) {
            for (path in paths) {
                val raw = fetchDirectUrl("$server$path") ?: continue
                try {
                    val sArr = JSONArray(raw.trim())
                    val seasons = parseSeasonsArray(sArr)
                    if (seasons.isNotEmpty()) return@withContext seasons
                } catch (_: Exception) {
                }
            }
        }
        emptyList()
    }

    /**
     * جستجوی کامل فیلم و سریال در کل دیتابیس سرور CCloud
     */
    suspend fun searchMedia(query: String): List<CCloudMediaItem> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext emptyList()
        val encoded = URLEncoder.encode(cleanQuery, "UTF-8").replace("+", "%20")

        val paths = listOf(
            "/api/search/$encoded/$CCLOUD_API_KEY/",
            "/api/search/$encoded/$CCLOUD_API_KEY"
        )

        for (server in CCLOUD_API_SERVERS) {
            for (path in paths) {
                val raw = fetchDirectUrl("$server$path") ?: continue
                try {
                    val trimmed = raw.trim()
                    val parsed = if (trimmed.startsWith("{")) {
                        val obj = JSONObject(trimmed)
                        val posters = obj.optJSONArray("posters") ?: JSONArray()
                        parsePostersJsonArray(posters, isSeriesDefault = false, isFromSearch = true)
                    } else if (trimmed.startsWith("[")) {
                        parsePostersJsonArray(JSONArray(trimmed), isSeriesDefault = false, isFromSearch = true)
                    } else {
                        emptyList()
                    }
                    if (parsed.isNotEmpty()) return@withContext parsed
                } catch (_: Exception) {
                }
            }
        }
        emptyList()
    }

    private fun parsePostersArray(raw: String, isSeriesDefault: Boolean): List<CCloudMediaItem> {
        return try {
            parsePostersJsonArray(JSONArray(raw.trim()), isSeriesDefault, isFromSearch = false)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parsePostersJsonArray(
        jsonArray: JSONArray,
        isSeriesDefault: Boolean,
        isFromSearch: Boolean
    ): List<CCloudMediaItem> {
        val list = mutableListOf<CCloudMediaItem>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.optJSONObject(i) ?: continue
            val id = obj.optInt("id", i + 1)
            val title = obj.optString("title")
                .ifEmpty { obj.optString("name") }
                .ifEmpty { continue }

            val desc = obj.optString("description")
                .ifEmpty { obj.optString("overview") }
                .ifEmpty { obj.optString("plot") }
                .replace(Regex("<[^>]*>"), "")
                .trim()

            val year = obj.optInt("year", extractYearFromTitle(title))
            val imdb = obj.optDouble("imdb", obj.optDouble("rating", 0.0))
            val image = obj.optString("image")
                .ifEmpty { obj.optString("poster") }
                .ifEmpty { obj.optString("cover") }
                .ifEmpty { obj.optString("thumbnail") }
                .trim()
            val cover = obj.optString("cover").ifEmpty { image }.trim()

            val genres = mutableListOf<String>()
            obj.optJSONArray("genres")?.let { gArr ->
                for (g in 0 until gArr.length()) {
                    when (val gItem = gArr.opt(g)) {
                        is JSONObject -> {
                            val gId = gItem.optInt("id", 0)
                            val gTitle = gItem.optString("title").trim()
                            if (gTitle.isNotEmpty()) {
                                genres.add(gTitle)
                                if (gId > 0) discoveredGenres[gId] = gTitle
                            }
                        }
                        is String -> if (gItem.isNotBlank()) genres.add(gItem.trim())
                    }
                }
            }

            val sources = parseSources(obj)
            val seasons = obj.optJSONArray("seasons")?.let { parseSeasonsArray(it) } ?: emptyList()
            val typeStr = obj.optString("type", "").lowercase()

            // در نتایج جستجوی سرور، سریال‌ها دارای type="serie" هستند یا فیلد sources آن‌ها خالی است (چون لینک‌ها داخل فصل‌هاست)
            val isSeries = isSeriesDefault ||
                    typeStr.contains("serie") ||
                    typeStr.contains("tv") ||
                    seasons.isNotEmpty() ||
                    (isFromSearch && sources.isEmpty())

            list.add(
                CCloudMediaItem(
                    id = id,
                    title = title,
                    description = desc,
                    year = year,
                    imdb = if (imdb.isNaN()) 0.0 else imdb,
                    image = image,
                    cover = cover,
                    isSeries = isSeries,
                    genres = genres.distinct(),
                    sources = sources,
                    seasons = seasons
                )
            )
        }
        return list
    }

    private fun parseSources(obj: JSONObject): List<CCloudSourceItem> {
        val sources = mutableListOf<CCloudSourceItem>()
        obj.optJSONArray("sources")?.let { sArr ->
            for (j in 0 until sArr.length()) {
                val sObj = sArr.optJSONObject(j) ?: continue
                val rawUrl = sObj.optString("url").ifEmpty { sObj.optString("link") }.trim()
                if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
                    val qualityLabel = sObj.optString("quality")
                        .takeIf { it.isNotBlank() && it != "null" }
                        ?: sObj.optString("title").takeIf { it.isNotBlank() && it != "null" }
                        ?: "HD"
                    sources.add(
                        CCloudSourceItem(
                            quality = qualityLabel,
                            url = sanitizeStreamUrl(rawUrl),
                            type = sObj.optString("type", "mp4")
                        )
                    )
                }
            }
        }
        if (sources.isEmpty()) {
            val singleUrl = obj.optString("url")
                .ifEmpty { obj.optString("link") }
                .ifEmpty { obj.optString("stream") }
                .ifEmpty { obj.optString("file") }
                .trim()
            if (singleUrl.startsWith("http://") || singleUrl.startsWith("https://")) {
                sources.add(CCloudSourceItem(quality = "Play HD", url = sanitizeStreamUrl(singleUrl)))
            }
        }
        return sources
    }

    private fun parseSeasonsArray(sArr: JSONArray): List<CCloudSeasonItem> {
        val seasons = mutableListOf<CCloudSeasonItem>()
        for (i in 0 until sArr.length()) {
            val sObj = sArr.optJSONObject(i) ?: continue
            val episodes = mutableListOf<CCloudEpisodeItem>()
            sObj.optJSONArray("episodes")?.let { eArr ->
                for (j in 0 until eArr.length()) {
                    val eObj = eArr.optJSONObject(j) ?: continue
                    val epSources = parseSources(eObj)
                    if (epSources.isNotEmpty()) {
                        episodes.add(
                            CCloudEpisodeItem(
                                episodeNumber = j + 1,
                                title = eObj.optString("title").ifEmpty { "Episode ${j + 1}" },
                                description = eObj.optString("description").takeIf { it != "null" } ?: "",
                                image = eObj.optString("image").takeIf { it != "null" } ?: "",
                                sources = epSources
                            )
                        )
                    }
                }
            }
            if (episodes.isNotEmpty()) {
                seasons.add(
                    CCloudSeasonItem(
                        seasonNumber = i + 1,
                        title = sObj.optString("title").ifEmpty { "Season ${i + 1}" },
                        episodes = episodes
                    )
                )
            }
        }
        return seasons
    }

    fun sanitizeStreamUrl(rawUrl: String): String =
        rawUrl.trim().replace(" ", "%20").replace("[", "%5B").replace("]", "%5D")

    private fun extractYearFromTitle(title: String): Int =
        Regex("""\b(19\d{2}|20\d{2})\b""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
}