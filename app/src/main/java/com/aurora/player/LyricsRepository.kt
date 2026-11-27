package com.aurora.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class LyricLine(val timeMs: Long, val text: String)

data class LyricsResult(
    val lines: List<LyricLine>,
    val plainText: String?,
    val instrumental: Boolean
)

object LyricsRepository {
    private val cache = ConcurrentHashMap<String, LyricsResult>()
    private val noResult = LyricsResult(emptyList(), null, false)

    suspend fun load(track: Track): LyricsResult = withContext(Dispatchers.IO) {
        val key = "${track.title.lowercase(Locale.ROOT)}|${track.artist.lowercase(Locale.ROOT)}|${track.durationMs / 1000}"
        cache[key]?.let { return@withContext it }
        val result = runCatching { requestExact(track) }.getOrNull() ?: noResult
        cache[key] = result
        result
    }

    private fun requestExact(track: Track): LyricsResult {
        val query = buildList {
            add("track_name" to track.title)
            add("artist_name" to track.artist)
            if (track.album.isNotBlank()) add("album_name" to track.album)
            if (track.durationMs > 0L) add("duration" to (track.durationMs / 1000L).toString())
        }.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }

        val connection = (URL("https://lrclib.net/api/get?$query").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 9000
            readTimeout = 12000
            setRequestProperty("User-Agent", "AuroraPlayer/1.0 (https://github.com/yorilii/iz__.q1)")
            setRequestProperty("Accept", "application/json")
        }

        try {
            val code = connection.responseCode
            if (code == 404) return noResult
            if (code == 429) return noResult
            if (code !in 200..299) return noResult
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(body)
            val instrumental = json.optBoolean("instrumental", false)
            val synced = json.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
            val plain = json.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
            return LyricsResult(
                lines = synced?.let(::parseLrc).orEmpty(),
                plainText = plain,
                instrumental = instrumental
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun parseLrc(source: String): List<LyricLine> {
        val timestamp = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\]""")
        return source.lineSequence().flatMap { rawLine ->
            val matches = timestamp.findAll(rawLine).toList()
            if (matches.isEmpty()) {
                emptyList()
            } else {
                val text = rawLine.substring(matches.last().range.last + 1).trim()
                matches.map { match ->
                    val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                    val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                    val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                    LyricLine((minutes * 60_000L) + (seconds * 1000L) + fraction, text)
                }
            }
        }.sortedBy { it.timeMs }.toList()
    }
}
