// C:/Users/my/AndroidStudioProjects/pureswr/app/src/main/java/myhilmyhill/pureswr/data/repository/SubsonicRepository.kt
package myhilmyhill.pureswr.data.repository

import io.ktor.client.* // HttpClient
import io.ktor.client.call.* // body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.* // ContentNegotiation
import io.ktor.client.request.* // get, parameter
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

// Domain Model Entry (unchanged)
@Serializable
sealed interface Entry {
    val id: String
    val name: String
}

@Serializable
data class FolderEntry(
    override val id: String,
    override val name: String,
    val entries: List<Entry>
) : Entry

@Serializable
data class MusicEntry(
    override val id: String,
    override val name: String
) : Entry

// Subsonic API DTOs
@Serializable
private data class SubsonicResponse( // Used by getMusicFolders, getMusicDirectory
    @SerialName("subsonic-response")
    val subsonicResponse: SubsonicResponseContent? = null
)

@Serializable
private data class SubsonicResponseContent( // Used by getMusicFolders, getMusicDirectory
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
data class SubsonicMusicFolderEntry(
    val id: String,
    val name: String? = null
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

// DTOs for getSong.view
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
    val duration: Int? = null, // Duration in seconds
    val bitRate: Int? = null,
    val path: String? = null,
    val playCount: Long? = null,
    val discNumber: Int? = null,
    val created: String? = null, // Timestamp
    val albumId: String? = null,
    val artistId: String? = null,
    val type: String? = null // e.g., "music"
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
    private val apiVersion = "1.16.1" // Or your target Subsonic API version
    private val restPath = "rest"
    private val clientName: String = "PureSWR"

    private val lenientJsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val strictJsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = false // For parsing error responses more strictly
    }

    private val client: HttpClient by lazy {
        httpClientOverride ?: HttpClient(CIO) {
            install(ContentNegotiation) {
                json(lenientJsonParser) // Default to lenient for primary parsing
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30000 // 30 seconds
                connectTimeoutMillis = 10000 // 10 seconds
                socketTimeoutMillis = 10000 // 10 seconds
            }
        }
    }

    suspend fun getFolderContents(folderId: String?): FolderEntry {
        val isRequestingRoot = folderId == null
        val endpoint = if (isRequestingRoot) "$restPath/getMusicFolders.view" else "$restPath/getMusicDirectory.view"

        var httpResponse: HttpResponse? = null
        var responseText: String? = null

        try {
            val localHttpResponse = client.get(baseUrl) {
                url {
                    appendPathSegments(endpoint)
                    parameters.append("u", username)
                    parameters.append("p", this@SubsonicRepository.password)
                    parameters.append("v", apiVersion)
                    parameters.append("c", clientName)
                    parameters.append("f", "json")
                    if (!isRequestingRoot) {
                        parameters.append("id", folderId!!)
                    }
                }
            }
            httpResponse = localHttpResponse
            
            val localResponseText = localHttpResponse.bodyAsText()
            responseText = localResponseText
            
            // Use lenientJsonParser for initial parsing
            val responseBody: SubsonicResponse = lenientJsonParser.decodeFromString(localResponseText)

            if (responseBody.subsonicResponse?.status == "ok") {
                if (isRequestingRoot) {
                    val musicFolderEntries = responseBody.subsonicResponse.musicFolders?.musicFolder?.map { dto ->
                        FolderEntry(
                            id = dto.id,
                            name = dto.name ?: "Unnamed Folder",
                            entries = emptyList()
                        )
                    } ?: emptyList()
                    return FolderEntry(
                        id = SYNTHETIC_ROOT_ID,
                        name = SYNTHETIC_ROOT_NAME,
                        entries = musicFolderEntries
                    )
                } else {
                    val directoryNode = responseBody.subsonicResponse.directory
                    if (directoryNode?.id != null && directoryNode.name != null) {
                        val entries = directoryNode.child?.mapNotNull { subsonicChild ->
                            val entryName = subsonicChild.name ?: subsonicChild.title ?: "Unknown Entry"
                            if (subsonicChild.isDir) {
                                FolderEntry(id = subsonicChild.id, name = entryName, entries = emptyList())
                            } else {
                                MusicEntry(id = subsonicChild.id, name = entryName)
                            }
                        } ?: emptyList()
                        return FolderEntry(id = directoryNode.id, name = directoryNode.name, entries = entries)
                    } else {
                        throw SubsonicApiException("Incomplete directory data for folderId '$folderId': ID or Name from server is null. Raw response: '$localResponseText'")
                    }
                }
            } else {
                val error = responseBody.subsonicResponse?.error
                val errorMessage = error?.message ?: "Subsonic API reported failure (no specific error message in parsed response)"
                val errorCode = error?.code
                throw SubsonicApiException("$errorMessage. Raw response: '$localResponseText'", errorCode)
            }
        } catch (e: SubsonicApiException) {
            throw e 
        } catch (e: ClientRequestException) { 
            val errorResponseText = e.response.bodyAsText()
            try {
                // Try parsing with strict parser for error structure
                val errorBody: SubsonicResponse = strictJsonParser.decodeFromString(errorResponseText)
                errorBody.subsonicResponse?.error?.let {
                    throw SubsonicApiException(it.message, it.code)
                }
                // Fallback if parsing errorBody or its structure is not as expected
                throw SubsonicApiException("HTTP Error ${e.response.status.value}: ${e.message}. Raw error body: '$errorResponseText'", e.response.status.value)
            } catch (parseEx: Exception) { 
                // If parsing the error response itself fails
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
            val localHttpResponse = client.get(baseUrl) {
                url {
                    appendPathSegments(endpoint)
                    parameters.append("id", songId)
                    parameters.append("u", username)
                    parameters.append("p", this@SubsonicRepository.password)
                    parameters.append("v", apiVersion)
                    parameters.append("c", clientName)
                    parameters.append("f", "json")
                }
            }
            httpResponse = localHttpResponse
            responseText = localHttpResponse.bodyAsText()

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
                val errorBody: SubsonicGetSongResponse = strictJsonParser.decodeFromString(errorResponseText) // Attempt to parse with specific error DTO
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
        if (httpClientOverride == null) { // Only close if this class created the client
            client.close()
        }
    }
}
