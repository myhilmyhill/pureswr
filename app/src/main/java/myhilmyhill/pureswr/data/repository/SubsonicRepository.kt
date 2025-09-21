// C:/Users/my/AndroidStudioProjects/pureswr/app/src/main/java/myhilmyhill/pureswr/data/repository/SubsonicRepository.kt
package myhilmyhill.pureswr.data.repository

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.ktor.client.* // HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.* // ContentNegotiation
import io.ktor.client.request.* // get, parameter, HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.* // appendPathSegments, URLBuilder
import io.ktor.serialization.kotlinx.json.json // json
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import io.ktor.client.plugins.HttpTimeout

class SubsonicApiException(message: String, val code: Int? = null) : Exception(message)

@Serializable
sealed interface Entry {
    val id: String
    val name: String
}

@Serializable
data class FolderEntry(
    override val id: String,
    override val name: String,
    val entries: List<Entry>,
    val parentFolderId: String? = null
) : Entry

@Serializable
data class MusicEntry(
    override val id: String,
    override val name: String,
    val dir: String
) : Entry

@Serializable
private data class SubsonicResponse(
    @SerialName("subsonic-response")
    val subsonicResponse: SubsonicResponseContent? = null
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
    val parent: String? = null,
    val path: String? = null,
    val child: List<SubsonicChildEntry>? = null
)

@Serializable
private data class SubsonicChildEntry(
    val id: String,
    val parent: String? = null,
    val isDir: Boolean,
    val title: String? = null,
    val path: String? = null,
    val artist: String? = null,
    val album: String? = null,
)

@Serializable
private data class SubsonicError(
    val code: Int,
    val message: String
)

@Serializable
data class SubsonicApiSong(
    val id: String,
    val parent: String? = null,
    val isDir: Boolean = false,
    val title: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val track: Int? = null,
    val year: Int? = null,
    val coverArt: String? = null,
    val size: Long? = null,
    val contentType: String? = null,
    val suffix: String? = null,
    val duration: Int? = null,
    val bitRate: Int? = null,
    val path: String? = null,
    val playCount: Long? = null,
    val discNumber: Int? = null,
    val created: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val type: String? = null
)

@Serializable
private data class SubsonicSongPayload(
    val status: String,
    val version: String? = null,
    val song: SubsonicApiSong? = null,
    val error: SubsonicError? = null
)

@Serializable
private data class SubsonicGetSongResponse(
    @SerialName("subsonic-response")
    val response: SubsonicSongPayload? = null
)

class SubsonicRepository(
    internal val baseUrl: String,
    internal val username: String,
    private val password: String,
    private val httpClientOverride: HttpClient? = null
) {
    companion object {
        const val SYNTHETIC_ROOT_ID = "al-1"
    }
    private val apiVersion = "1.16.1"
    private val restPath = "rest"
    private val clientName: String = "pureswr"

    private val lenientJsonParser = Json {
        ignoreUnknownKeys = true
    }

    private val client: HttpClient by lazy {
        httpClientOverride ?: HttpClient(CIO) {
            install(ContentNegotiation) {
                json(lenientJsonParser)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 10000
            }
        }
    }

    private suspend fun performHttpRequest(
        endpoint: String,
        requestSetup: HttpRequestBuilder.() -> Unit = {}
    ): HttpResponse {
        return client.get(baseUrl) {
            url {
                appendPathSegments(endpoint)
            }
            url.parameters.apply {
                append("u", username)
                append("p", this@SubsonicRepository.password)
                append("v", apiVersion)
                append("c", clientName)
                append("f", "json")
            }
            requestSetup()
        }
    }

    suspend fun getFolderContents(folderId: String?): FolderEntry {
        val actualFolderId = folderId ?: SYNTHETIC_ROOT_ID
        val endpoint = "$restPath/getMusicDirectory.view"
        val httpResponse = performHttpRequest(endpoint) {
            url.parameters.append("id", actualFolderId)
        }
        val responseText = httpResponse.bodyAsText()

        val responseBody: SubsonicResponse = try {
            lenientJsonParser.decodeFromString(responseText)
        } catch (e: SerializationException) {
            throw SubsonicApiException(
                message = "Failed to deserialize the response body with lenient parsing. Original deserialization error: ${e.message}. Raw response body: '$responseText'",
                code = null
            )
        }

        if (responseBody.subsonicResponse?.status == "ok") {
            val directoryNode = responseBody.subsonicResponse.directory
                ?: throw SubsonicApiException("Directory data is null for folderId '$actualFolderId'. Raw response: '$responseText'")

            val entries = directoryNode.child?.map { subsonicChild ->
                if (subsonicChild.isDir) {
                    FolderEntry(
                        id = subsonicChild.id ?: throw SubsonicApiException("subsonicChild.id is null. Raw response: '$responseText'"),
                        name = subsonicChild.title ?: throw SubsonicApiException("subsonicChild.title are null. Raw response: '$responseText'"),
                        entries = emptyList(),
                        parentFolderId = if (subsonicChild.parent == "-1") null else subsonicChild.parent
                    )
                } else {
                    val fullPath = subsonicChild.path ?: ""
                    val name = fullPath.substringAfterLast("/")
                    // Using the version of 'dir' calculation currently in your file
                    val dir = fullPath.substringBeforeLast("/", missingDelimiterValue = "/").ifEmpty { "/" }
                    MusicEntry(
                        id = subsonicChild.id ?: throw SubsonicApiException("subsonicChild.id is null for music entry. Raw response: '$responseText'"),
                        name = name,
                        dir = dir,
                    )
                }
            } ?: emptyList()

            return FolderEntry(
                id = directoryNode.id ?: throw SubsonicApiException("directoryNode.id is null. Raw response: '$responseText'"),
                name = directoryNode.name ?: throw SubsonicApiException("directoryNode.name is null. Raw response: '$responseText'"),
                entries = entries,
                parentFolderId = if (directoryNode.parent == "-1") null else directoryNode.parent
            )
        } else {
            val error = responseBody.subsonicResponse?.error
            val errorMessage = error?.message ?: "Subsonic API reported failure (no specific error message in parsed response)"
            val errorCode = error?.code
            throw SubsonicApiException("$errorMessage. Raw response: '$responseText'", errorCode)
        }
    }

    suspend fun getSongDetails(songId: String): SubsonicApiSong {
        val endpoint = "$restPath/getSong.view"
        val httpResponse = performHttpRequest(endpoint) {
            url.parameters.append("id", songId)
        }
        val responseText = httpResponse.bodyAsText()
        val responseBody: SubsonicGetSongResponse = try {
            lenientJsonParser.decodeFromString(responseText)
        } catch (e: SerializationException) {
            throw SubsonicApiException(
                message = "Failed to deserialize getSong.view response body. Original error: ${e.message}. Raw response: '$responseText'",
                code = null
            )
        }

        if (responseBody.response?.status == "ok") {
            return responseBody.response.song ?: throw SubsonicApiException("Song data is null in successful getSong.view response. Raw response: '$responseText'")
        } else {
            val error = responseBody.response?.error
            val errorMessage = error?.message ?: "Subsonic API reported failure for getSong (no specific error message in parsed response)"
            val errorCode = error?.code
            throw SubsonicApiException("$errorMessage. Raw response: '$responseText'", errorCode)
        }
    }

    fun getStreamMediaItem(songId: String): MediaItem.Builder {
        val streamUrl = URLBuilder(baseUrl).apply {
            appendPathSegments(restPath, "stream.view")
            parameters.append("id", songId)
            parameters.append("u", username)
            parameters.append("p", this@SubsonicRepository.password) // Consider security implications of password in URL
            parameters.append("v", apiVersion)
            parameters.append("c", clientName)
        }.build().toString()
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(streamUrl.toUri())
            .setMediaId(songId)
        return mediaItemBuilder
    }

    fun close() {
        if (httpClientOverride == null) { 
            client.close()
        }
    }
}
