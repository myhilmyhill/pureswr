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
        const val SYNTHETIC_ROOT_ID = "__SUBSONIC_ROOT__"
        const val SYNTHETIC_ROOT_NAME = "Subsonic Library"
    }
    private val apiVersion = "1.16.1"
    private val restPath = "rest"
    private val clientName: String = "PureSWR"

    private val lenientJsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val strictJsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    private fun HttpRequestBuilder.commonParameters() {
        url.parameters.apply {
            append("u", username)
            append("p", this@SubsonicRepository.password)
            append("v", apiVersion)
            append("c", clientName)
            append("f", "json")
        }
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
            commonParameters()
            requestSetup()
        }
    }

    suspend fun getFolderContents(folderId: String?): FolderEntry {
        val isRequestingRoot = folderId == null
        val endpoint = if (isRequestingRoot) "$restPath/getMusicFolders.view" else "$restPath/getMusicDirectory.view"

        var httpResponse: HttpResponse? = null
        var responseText: String? = null

        try {
            httpResponse = performHttpRequest(endpoint) {
                if (!isRequestingRoot) {
                    url.parameters.append("id", folderId!!)
                }
            }
            
            responseText = httpResponse.bodyAsText()
            val responseBody: SubsonicResponse = lenientJsonParser.decodeFromString(responseText)

            if (responseBody.subsonicResponse?.status == "ok") {
                if (isRequestingRoot) {
                    val musicFolderEntries = responseBody.subsonicResponse.musicFolders?.musicFolder?.map { dto ->
                        FolderEntry(
                            id = dto.id,
                            name = dto.name ?: "Unnamed Folder",
                            entries = emptyList(),
                            parentFolderId = null
                        )
                    } ?: emptyList()
                    return FolderEntry(
                        id = SYNTHETIC_ROOT_ID,
                        name = SYNTHETIC_ROOT_NAME,
                        entries = musicFolderEntries,
                        parentFolderId = null // ★ Synthetic root has no parent
                    )
                } else { // Requesting a specific directory
                    val directoryNode = responseBody.subsonicResponse.directory
                    if (directoryNode?.id != null && directoryNode.name != null) {
                        val entries = directoryNode.child?.mapNotNull { subsonicChild ->
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
                            id = directoryNode.id,
                            name = directoryNode.name,
                            entries = entries,
                            parentFolderId = if (directoryNode.parent == "-1") null else directoryNode.parent
                        )
                    } else {
                        throw SubsonicApiException("Incomplete directory data for folderId '$folderId': ID or Name from server is null. Raw response: '$responseText'")
                    }
                }
            } else {
                val error = responseBody.subsonicResponse?.error
                val errorMessage = error?.message ?: "Subsonic API reported failure (no specific error message in parsed response)"
                val errorCode = error?.code
                throw SubsonicApiException("$errorMessage. Raw response: '$responseText'", errorCode)
            }
        } catch (e: SubsonicApiException) {
            throw e 
        } catch (e: ClientRequestException) { 
            val errorResponseText = e.response.bodyAsText()
            try {
                val errorBody: SubsonicResponse = strictJsonParser.decodeFromString(errorResponseText)
                errorBody.subsonicResponse?.error?.let {
                    throw SubsonicApiException(it.message, it.code)
                }
                throw SubsonicApiException("HTTP Error ${e.response.status.value}: ${e.message}. Raw error body: '$errorResponseText'", e.response.status.value)
            } catch (parseEx: Exception) { 
                throw SubsonicApiException("HTTP Error ${e.response.status.value}: ${e.message}. Failed to parse error response body: '$errorResponseText'. Parse Error: ${parseEx.message}", e.response.status.value)
            }
        } catch (e: SerializationException) { 
            val status = httpResponse?.status?.value ?: "Unknown Status (httpResponse was null)"
            val rawText = responseText ?: "Unknown Body (responseText was null)"
            throw SubsonicApiException(
                message = "Received HTTP $status, but failed to deserialize the response body with lenient parsing. " +
                          "Original deserialization error: ${e.message}. Raw response body: '$rawText'",
                code = null 
            )
        } catch (e: Exception) { 
            val status = httpResponse?.status?.value ?: "Unknown Status (httpResponse was null)"
            val rawTextInfo = responseText?.let { "Raw response: '$it'" } ?: "Response text not available (responseText was null)."
            throw SubsonicApiException("An unexpected general error occurred. HTTP Status: $status. Error: ${e.message ?: "Unknown error type"}. $rawTextInfo")
        }
    }

    suspend fun getSongDetails(songId: String): SubsonicApiSong {
        val endpoint = "$restPath/getSong.view"
        var httpResponse: HttpResponse? = null
        var responseText: String? = null

        try {
            httpResponse = performHttpRequest(endpoint) {
                url.parameters.append("id", songId)
            }
            
            responseText = httpResponse.bodyAsText()
            val responseBody: SubsonicGetSongResponse = lenientJsonParser.decodeFromString(responseText)

            if (responseBody.response?.status == "ok") {
                return responseBody.response.song ?: throw SubsonicApiException("Song data is null in successful response. Raw response: '$responseText'")
            } else {
                val error = responseBody.response?.error
                val errorMessage = error?.message ?: "Subsonic API reported failure for getSong (no specific error message in parsed response)"
                val errorCode = error?.code
                throw SubsonicApiException("$errorMessage. Raw response: '$responseText'", errorCode)
            }
        } catch (e: SubsonicApiException) {
            throw e
        } catch (e: ClientRequestException) {
            val errorResponseText = e.response.bodyAsText()
            try {
                val errorBody: SubsonicGetSongResponse = strictJsonParser.decodeFromString(errorResponseText) 
                errorBody.response?.error?.let {
                    throw SubsonicApiException(it.message, it.code)
                }
                throw SubsonicApiException("HTTP Error ${e.response.status.value}: ${e.message}. Raw error body: '$errorResponseText'", e.response.status.value)
            } catch (parseEx: Exception) {
                throw SubsonicApiException("HTTP Error ${e.response.status.value}: ${e.message}. Failed to parse error response body for getSong: '$errorResponseText'. Parse Error: ${parseEx.message}", e.response.status.value)
            }
        } catch (e: SerializationException) {
            val status = httpResponse?.status?.value ?: "Unknown Status (httpResponse was null)"
            val rawText = responseText ?: "Unknown Body (responseText was null)"
            throw SubsonicApiException(
                message = "Received HTTP $status for getSong, but failed to deserialize the response body. " +
                          "Original deserialization error: ${e.message}. Raw response body: '$rawText'",
                code = null
            )
        } catch (e: Exception) {
            val status = httpResponse?.status?.value ?: "Unknown Status (httpResponse was null)"
            val rawTextInfo = responseText?.let { "Raw response: '$it'" } ?: "Response text not available."
            throw SubsonicApiException("An unexpected error occurred in getSongDetails. HTTP Status: $status. Error: ${e.message ?: "Unknown error type"}. $rawTextInfo")
        }
    }

    fun close() {
        if (httpClientOverride == null) { 
            client.close()
        }
    }
}
