// C:/Users/my/AndroidStudioProjects/pureswr/app/src/main/java/myhilmyhill/pureswr/data/repository/SubsonicRepository.kt
package myhilmyhill.pureswr.data.repository

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText // Kept for potential debugging
import io.ktor.http.*
import io.ktor.serialization.JsonConvertException // For specific catch / 'is' check
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException // For specific catch / 'is' check
import java.nio.charset.StandardCharsets
import io.ktor.client.call.NoTransformationFoundException // For specific catch / 'is' check

// Custom Exception for Subsonic API errors
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
    val entries: List<Entry>
) : Entry

@Serializable
data class MusicEntry(
    override val id: String,
    override val name: String
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

class SubsonicRepository(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
    private val clientName: String = "PureSWR",
    private val httpClientOverride: HttpClient? = null
) {
    private val apiVersion = "1.16.1"
    private val restPath = "rest"

    private val client: HttpClient by lazy {
        httpClientOverride ?: HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = false
                })
            }
        }
    }

    suspend fun getFolderContents(folderId: String?): FolderEntry {
        val endpoint = "$restPath/getMusicDirectory.view"
        val httpResponse: HttpResponse = client.get(baseUrl) {
            url {
                appendPathSegments(endpoint)
                parameters.append("u", username)
                parameters.append("p", this@SubsonicRepository.password)
                parameters.append("v", apiVersion)
                parameters.append("c", clientName)
                parameters.append("f", "json")
                folderId?.let { parameters.append("id", it) }
            }
        }

        val responseText = httpResponse.bodyAsText() // Keep for debugging if needed
        val responseBody: SubsonicResponse = try {
            httpResponse.body()
        } catch (e: Exception) {
            val message = e.message ?: "An error occurred without a specific message."
            throw SubsonicApiException(message, null) // 'code' is always null when caught here
        }

        if (responseBody.subsonicResponse?.status == "ok") {
            val directory = responseBody.subsonicResponse.directory
            if (directory?.id != null && directory.name != null) {
                val entries = directory.child?.mapNotNull { subsonicChild ->
                    if (subsonicChild.isDir) {
                        FolderEntry(
                            id = subsonicChild.id,
                            name = subsonicChild.title ?: subsonicChild.name ?: "Unknown Folder",
                            entries = emptyList() // Placeholder
                        )
                    } else {
                        MusicEntry(
                            id = subsonicChild.id,
                            name = subsonicChild.title ?: subsonicChild.name ?: "Unknown Track"
                        )
                    }
                } ?: emptyList()

                return FolderEntry(
                    id = directory.id,
                    name = directory.name,
                    entries = entries
                )
            } else {
                throw SubsonicApiException("Incomplete directory data in Subsonic response: ID or Name is null.")
            }
        } else {
            val error = responseBody.subsonicResponse?.error
            val errorMessage = error?.message ?: "Unknown Subsonic API error"
            val errorCode = error?.code
            throw SubsonicApiException(errorMessage, errorCode)
        }
    }

    fun close() {
        if (httpClientOverride == null) {
            client.close()
        } else {
            client.close()
        }
    }
}
