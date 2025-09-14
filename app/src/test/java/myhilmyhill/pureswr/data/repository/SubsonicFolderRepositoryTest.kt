// C:/Users/my/AndroidStudioProjects/pureswr/app/src/test/java/myhilmyhill/pureswr/data/repository/SubsonicFolderRepositoryTest.kt
package myhilmyhill.pureswr.data.repository

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json

class SubsonicFolderRepositoryTest {

    @Test
    fun `getFolderContents for root should return parsed list from mock response`() = runTest {
        val mockRootFolderJsonResponse = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "type": "myMockServer",
                "serverVersion": "1.0.0",
                "directory": {
                  "id": "0",
                  "name": "Root Music",
                  "child": [
                    {
                      "id": "folder-1",
                      "parent": "0",
                      "isDir": true,
                      "title": "Favorite Artists",
                      "name": "Favorite Artists",
                      "album": null,
                      "artist": null,
                      "coverArt": null
                    },
                    {
                      "id": "song-123",
                      "parent": "0",
                      "isDir": false,
                      "title": "Example Song Title",
                      "name": "Example Song.mp3",
                      "album": "Test Album",
                      "artist": "Mock Artist",
                      "coverArt": "artId-song-123"
                    }
                  ]
                }
              }
            }
            """.trimIndent()

        val mockEngine = MockEngine { request ->
            assertEquals("/rest/getMusicDirectory.view", request.url.encodedPath)
            assertNull("ID should be null for root folder request", request.url.parameters["id"])
            respond(
                content = ByteReadChannel(mockRootFolderJsonResponse.toByteArray(Charsets.UTF_8)),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
        }
        val repository = SubsonicRepository(
            baseUrl = "http://mock.server.com",
            username = "mockuser",
            password = "mockpassword",
            httpClientOverride = httpClient
        )

        val rootFolderEntry = repository.getFolderContents(null)

        assertEquals("Root folder should have ID '0'", "0", rootFolderEntry.id)
        assertEquals(
            "Root folder should have name 'Root Music'",
            "Root Music",
            rootFolderEntry.name
        )
        assertEquals(
            "Should return 2 items in the entries list from mock root response",
            2,
            rootFolderEntry.entries.size
        )

        val firstItem = rootFolderEntry.entries[0]
        assertTrue("First item should be a FolderEntry", firstItem is FolderEntry)
        val firstFolder = firstItem as FolderEntry
        assertEquals("folder-1", firstFolder.id)
        assertEquals("Favorite Artists", firstFolder.name)
        assertTrue(
            "Nested folder\'s entries should be empty for now",
            firstFolder.entries.isEmpty()
        )

        val secondItem = rootFolderEntry.entries[1]
        assertTrue("Second item should be a MusicEntry", secondItem is MusicEntry)
        val secondMusic = secondItem as MusicEntry
        assertEquals("song-123", secondMusic.id)
        assertEquals("Example Song Title", secondMusic.name)

        repository.close()
    }

    @Test
    fun `getFolderContents for an empty folder should return empty list`() = runTest {
        val mockEmptyFolderJsonResponse = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "type": "myMockServer",
                "serverVersion": "1.0.0",
                "directory": {
                  "id": "10",
                  "name": "Empty Folder",
                  "child": [] 
                }
              }
            }
            """.trimIndent()

        val mockEngine = MockEngine { request ->
            assertEquals("/rest/getMusicDirectory.view", request.url.encodedPath)
            assertEquals("folder-empty", request.url.parameters["id"])
            respond(
                content = ByteReadChannel(mockEmptyFolderJsonResponse.toByteArray(Charsets.UTF_8)),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
        }
        val repository = SubsonicRepository(
            baseUrl = "http://mock.server.com",
            username = "mockuser",
            password = "mockpassword",
            httpClientOverride = httpClient
        )

        val folderEntry = repository.getFolderContents("folder-empty")

        assertEquals("Folder ID should be '10'", "10", folderEntry.id)
        assertEquals("Folder name should be 'Empty Folder'", "Empty Folder", folderEntry.name)
        assertTrue(
            "Folder contents (entries list) should be empty for 'folder-empty'",
            folderEntry.entries.isEmpty()
        )

        repository.close()
    }

    @Test
    fun `getFolderContents with auth error should throw SubsonicApiException`() = runTest {
        val mockAuthErrorJsonResponse = """
            {
              "subsonic-response": {
                "status": "failed",
                "version": "1.16.1",
                "type": "myMockServer",
                "serverVersion": "1.0.0",
                "error": {
                  "code": 40,
                  "message": "Mocked: Incorrect username or password."
                }
              }
            }
            """.trimIndent()

        val mockEngine = MockEngine { request ->
            assertEquals("/rest/getMusicDirectory.view", request.url.encodedPath)
            assertEquals("folder-error-auth", request.url.parameters["id"])
            respond(
                content = ByteReadChannel(mockAuthErrorJsonResponse.toByteArray(Charsets.UTF_8)),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
        }
        val repository = SubsonicRepository(
            baseUrl = "http://mock.server.com",
            username = "mockuser",
            password = "mockpassword",
            httpClientOverride = httpClient
        )

        try {
            repository.getFolderContents("folder-error-auth")
            fail("SubsonicApiException was expected but not thrown.")
        } catch (e: SubsonicApiException) {
            assertEquals("Mocked: Incorrect username or password.", e.message)
            assertEquals(40, e.code)
        } finally {
            repository.close()
        }
    }

    @Test
    fun `getFolderContents with malformed JSON should throw SubsonicApiException`() = runTest {
        val malformedJsonResponse =
            """{"subsonic-response": {"status": "ok", "directory": {"id":"1" """ // Incomplete JSON

        val mockEngine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(malformedJsonResponse.toByteArray(Charsets.UTF_8)),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true; isLenient = false
                })
            }
        }
        val repository =
            SubsonicRepository("http://mock.server.com", "u", "p", httpClientOverride = httpClient)

        try {
            repository.getFolderContents("anyId")
            fail("SubsonicApiException was expected due to malformed JSON but not thrown.")
        } catch (e: SubsonicApiException) {
            val actualMessage = e.message ?: ""
            assertTrue(
                "Exception message should indicate malformed JSON. Actual: $actualMessage",
                actualMessage.startsWith("Expected end of the object '}', but had 'EOF' instead at path:")
            )
            assertNull(
                "Exception code should be null for this type of error. Actual: ${e.code}",
                e.code
            )
        } finally {
            repository.close()
        }
    }

    @Test
    fun `getFolderContents with missing directory id in ok response should throw SubsonicApiException`() =
        runTest {
            val mockIncompleteDataResponse = """
                {
                  "subsonic-response": {
                    "status": "ok",
                    "version": "1.16.1",
                    "directory": {
                      "name": "Folder With Missing ID"
                    }
                  }
                }
                """.trimIndent() // Removed the comment line that was causing a parsing error

            val mockEngine = MockEngine { _ ->
                respond(
                    content = ByteReadChannel(mockIncompleteDataResponse.toByteArray(Charsets.UTF_8)),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val repository = SubsonicRepository(
                "http://mock.server.com",
                "u",
                "p",
                httpClientOverride = httpClient
            )

            try {
                repository.getFolderContents("anyId")
                fail("SubsonicApiException was expected due to incomplete directory data but not thrown.")
            } catch (e: SubsonicApiException) {
                assertEquals(
                    "Incomplete directory data in Subsonic response: ID or Name is null.",
                    e.message
                )
            } finally {
                repository.close()
            }
        }

    @Test
    fun `getFolderContents with 200 OK but completely unexpected JSON structure should throw SubsonicApiException`() =
        runTest {
            val unexpectedJsonResponse = """
                {
                  "data": {
                    "message": "This is not a Subsonic response",
                    "value": 123
                  }
                }
                """.trimIndent()

            val mockEngine = MockEngine { request ->
                assertEquals("/rest/getMusicDirectory.view", request.url.encodedPath)
                respond(
                    content = ByteReadChannel(unexpectedJsonResponse.toByteArray(Charsets.UTF_8)),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    })
                }
            }
            val repository = SubsonicRepository(
                baseUrl = "http://mock.server.com",
                username = "mockuser",
                password = "mockpassword",
                httpClientOverride = httpClient
            )

            try {
                repository.getFolderContents("anyFolderId")
                fail("SubsonicApiException was expected due to completely unexpected JSON structure but not thrown.")
            } catch (e: SubsonicApiException) {
                // Because subsonicResponse field itself will be null after deserialization of unexpected JSON
                // the code will fall into the 'else' branch of 'if (responseBody.subsonicResponse?.status == "ok")'
                // and then try to access responseBody.subsonicResponse?.error, which will also be null.
                assertEquals("Unknown Subsonic API error", e.message)
                assertNull("Error code should be null for this type of structural error", e.code)
            } finally {
                repository.close()
            }
        }

    @Test
    fun `getFolderContents with 200 OK but plain text response should throw SubsonicApiException`() =
        runTest {
            val plainTextResponse = "This is not JSON, this is plain text."

            val mockEngine = MockEngine { request ->
                assertEquals("/rest/getMusicDirectory.view", request.url.encodedPath)
                respond(
                    content = ByteReadChannel(plainTextResponse.toByteArray(Charsets.UTF_8)),
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.ContentType,
                        ContentType.Text.Plain.toString()
                    ) // Explicitly set Content-Type
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(Json {
                        ignoreUnknownKeys = true
                        isLenient = true // isLenient might not save it from non-JSON
                    })
                }
            }
            val repository = SubsonicRepository(
                baseUrl = "http://mock.server.com",
                username = "mockuser",
                password = "mockpassword",
                httpClientOverride = httpClient
            )

            try {
                repository.getFolderContents("anyFolderId")
                fail("SubsonicApiException was expected due to plain text response but not thrown.")
            } catch (e: SubsonicApiException) {
                val actualMessage = e.message ?: ""
                assertTrue(
                    "Exception message should indicate content conversion failure. Actual: $actualMessage",
                    actualMessage.startsWith("Expected response body of the type 'class myhilmyhill.pureswr.data.repository.SubsonicResponse (Kotlin reflection is not available)'")
                )
                assertNull(
                    "Exception code should be null for this type of error. Actual: ${e.code}",
                    e.code
                )
            } finally {
                repository.close()
            }
        }
}
