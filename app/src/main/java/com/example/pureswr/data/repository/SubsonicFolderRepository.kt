// com/example/pureswr/data/repository/SubsonicFolderRepository.kt
package com.example.pureswr.data.repository

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.* // Or other engines like OkHttp, Android
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json // For Json { ignoreUnknownKeys = true }

// kotlinx.serialization.json を使用するために @Serializable アノテーションを付与
@Serializable
data class MusicDirectoryEntry(
    val id: String,
    val name: String, // 'title' or 'name' from API
    val isDir: Boolean,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val coverArt: String? = null
)

// Subsonic APIのレスポンス構造に合わせた中間データクラス
@Serializable
private data class SubsonicResponse(
    val subsonicResponse: SubsonicResponseContent? = null // APIルート要素 "subsonic-response"
)

@Serializable
private data class SubsonicResponseContent(
    val status: String,
    val version: String? = null,
    val directory: SubsonicDirectory? = null,
    val error: SubsonicError? = null
)

@Serializable
private data class SubsonicDirectory(
    val id: String? = null,
    val name: String? = null,
    val child: List<SubsonicChildEntry>? = null
)

@Serializable
private data class SubsonicChildEntry(
    val id: String,
    val parent: String? = null,
    val isDir: Boolean,
    val title: String? = null, // Often used for song title or directory name
    val name: String? = null,  // Sometimes used as fallback or for different item types
    val artist: String? = null,
    val album: String? = null,
    val coverArt: String? = null
    // 他の必要なフィールドもここに追加 (duration, sizeなど)
)

@Serializable
private data class SubsonicError(
    val code: Int,
    val message: String
)


class SubsonicFolderRepository(
    private val baseUrl: String, // e.g., "http://your-subsonic-server.com" (without /rest)
    private val username: String,
    private val token: String,
    private val salt: String,
    private val clientName: String = "PureSWR"
) {
    private val apiVersion = "1.16.1"
    private val restPath = "rest" // Subsonic API path, e.g. /rest

    private val client = HttpClient(CIO) { // Or HttpClient(Android) for Android engine
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true // APIレスポンスに未知のキーがあってもエラーにしない
                isLenient = true // JSON形式のエラーに多少寛容にする
            })
        }
        // Base URL設定やデフォルトヘッダーなどもここで設定可能
        // developmentMode = true // Log Ktor network requests and responses
    }

    suspend fun getFolderContents(folderId: String?): List<MusicDirectoryEntry> {
        val endpoint = "$restPath/getMusicDirectory.view"
        try {
            val response: SubsonicResponse = client.get(baseUrl) {
                url {
                    appendPathSegments(endpoint)
                    parameters.append("u", username)
                    parameters.append("t", token)
                    parameters.append("s", salt)
                    parameters.append("v", apiVersion)
                    parameters.append("c", clientName)
                    parameters.append("f", "json")
                    folderId?.let { parameters.append("id", it) }
                }
            }.body() // Ktorが自動的にJSONをSubsonicResponseに変換

            if (response.subsonicResponse?.status == "ok") {
                return response.subsonicResponse.directory?.child?.map { child ->
                    MusicDirectoryEntry(
                        id = child.id,
                        name = child.title ?: child.name ?: "Unknown", // title優先、なければname
                        isDir = child.isDir,
                        title = child.title,
                        artist = child.artist,
                        album = child.album,
                        coverArt = child.coverArt
                    )
                } ?: emptyList()
            } else {
                val error = response.subsonicResponse?.error
                // Log.e("SubsonicAPI", "API Error: ${error?.message} (code ${error?.code})")
                println("API Error: ${error?.message} (code ${error?.code})") // For testing
                return emptyList()
            }
        } catch (e: Exception) {
            // Log.e("SubsonicAPI", "Network or parsing error", e)
             println("Network or parsing error: ${e.message}") // For testing
            return emptyList()
        }
    }

    // 必要に応じてHttpClientをクローズするメソッド (Application終了時など)
    fun close() {
        client.close()
    }
}
