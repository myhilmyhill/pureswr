// C:/Users/my/AndroidStudioProjects/pureswr/app/src/main/java/myhilmyhill/pureswr/data/repository/SubsonicRepository.kt
package myhilmyhill.pureswr.data.repository

import io.ktor.client.* // HttpClient
import io.ktor.client.call.* // body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.* // ContentNegotiation
import io.ktor.client.request.* // get, parameter, HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.* // appendPathSegments
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
    val parentFolderId: String? = null // ★ parentFolderId を追加
) : Entry

@Serializable
data class MusicEntry(
    override val id: String,
    override val name: String
    // parentFolderId は MusicEntry には通常不要
) : Entry

// Subsonic API DTOs (変更なし)
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
    val musicFolders: SubsonicMusicFolders? = null,
    val error: SubsonicError? = null
)

@Serializable
private data class SubsonicMusicFolders(
    @SerialName("musicFolder")
    val musicFolder: List<SubsonicMusicFolderEntry>? = null
)

@Serializable
data class SubsonicMusicFolderEntry( // This DTO is for top-level music folders from getMusicFolders.view
    val id: String,
    val name: String? = null
    // It does not have a 'parent' field from Subsonic, we infer it.
)

@Serializable
private data class SubsonicDirectory( // This DTO is for a specific directory from getMusicDirectory.view
    val id: String? = null, // The ID of this directory
    val name: String? = null, // The name of this directory
    val parent: String? = null, // The ID of the parent directory
    val child: List<SubsonicChildEntry>? = null // Children of this directory
)

@Serializable
private data class SubsonicChildEntry( // This DTO is for an entry within a SubsonicDirectory
    val id: String,
    val parent: String? = null, // ID of the directory containing this child entry
    val isDir: Boolean,
    val title: String? = null,
    val name: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val coverArt: String? = null
)

@Serializable
private data class SubsonicError(
    val code: Int,
    val message: String
)

// DTOs for getSong.view (変更なし)
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
        const val SYNTHETIC_ROOT_NAME = "Subsonic Library"
    }
    private val apiVersion = "1.16.1"
    private val restPath = "rest"
    private val clientName: String = "PureSWR"

    private val lenientJsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
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
        val httpResponse= performHttpRequest(endpoint) {
            url.parameters.append("id", actualFolderId)
        }
        val responseText = httpResponse.bodyAsText()
        val responseBody: SubsonicResponse = lenientJsonParser.decodeFromString(responseText)

        if (responseBody.subsonicResponse?.status == "ok") {
            val directoryNode = responseBody.subsonicResponse.directory
                ?: throw SubsonicApiException("Directory data is null for folderId '$actualFolderId'. Raw response: '$responseText'")
            val entries = directoryNode.child?.map { subsonicChild ->
                val entryName = subsonicChild.name ?: subsonicChild.title ?: "Unknown Entry"
                if (subsonicChild.isDir) {
                    FolderEntry(
                        id = subsonicChild.id,
                        name = entryName,
                        entries = emptyList(),
                        parentFolderId = if (subsonicChild.parent == "-1") null else subsonicChild.parent
                    )
                } else {
                    MusicEntry(id = subsonicChild.id, name = entryName)
                }
            } ?: emptyList()

            return FolderEntry(
                id = directoryNode.id ?: throw SubsonicApiException("directoryNode.id is null "),
                name = directoryNode.name ?: throw SubsonicApiException("directoryNode.name is null "),
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
        val responseBody: SubsonicGetSongResponse = lenientJsonParser.decodeFromString(responseText)

        if (responseBody.response?.status == "ok") {
            return responseBody.response.song ?: throw SubsonicApiException("Song data is null in successful response. Raw response: '$responseText'")
        } else {
            val error = responseBody.response?.error
            val errorMessage = error?.message ?: "Subsonic API reported failure for getSong (no specific error message in parsed response)"
            val errorCode = error?.code
            throw SubsonicApiException("$errorMessage. Raw response: '$responseText'", errorCode)
        }
    }

    fun close() {
        if (httpClientOverride == null) { 
            client.close()
        }
    }
}
