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

class SubsonicRepositoryTest {
    @Test
    fun `getFolderContents_producesCorrectEffectiveFolderNames`() = runTest {
        val mockJsonResponse = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "directory": {
                  "id": "subfolder-id-123",
                  "name": "My Collection", 
                  "parent": "subfolder-id-0",
                  "child": [
                    { "id": "folder-music", "parent": "subfolder-id-123", "isDir": true, "title": "Music" },
                    { "id": "file-song", "parent": "subfolder-id-123", "isDir": false, "title": "My Great Song", "path": "/My Collection/123/My_Great_Song.mp3" }
                  ]
                }
              }
            }
            """.trimIndent()

        val mockEngine = MockEngine { request ->
            assertEquals("/rest/getMusicDirectory.view", request.url.encodedPath)
            assertEquals("subfolder-id-123", request.url.parameters["id"])
            respond(
                content = ByteReadChannel(mockJsonResponse.toByteArray(Charsets.UTF_8)),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val httpClient = HttpClient(mockEngine)
        val repository = SubsonicRepository("http://test.com", "u", "p", httpClient)
        val parentFolderResult = repository.getFolderContents("subfolder-id-123")

        val childMusic = parentFolderResult.entries[0]
        assertEquals("Music", childMusic.name)

        val childSong = parentFolderResult.entries[1]
        assertEquals("My_Great_Song.mp3", childSong.name)

        repository.close()
    }

    @Test
    fun `getFolderContents for root should return parsed list from mock response`() = runTest {
        val mockRootFolderJsonResponse = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "directory": {
                  "id": "al-1",
                  "name": ".",
                  "parent": null,
                  "child": [
                    { "id": "folder-1", "parent": "al-1", "isDir": false, "title": "Favorite Artists", "path": "/Favorite_Artists.mp3" },
                    { "id": "folder-2", "parent": "al-1", "isDir": false, "title": "Cool Mixtapes", "path": "/Cool_Mixtapes.opus" }
                  ]
                }
              }
            }
            """.trimIndent()

        val mockEngine = MockEngine { request ->
            assertEquals("URL path mismatch for root request", "/rest/getMusicDirectory.view", request.url.encodedPath)
            assertEquals("ID should be 'al-1' for root folder request", "al-1", request.url.parameters["id"])
            respond(
                content = ByteReadChannel(mockRootFolderJsonResponse.toByteArray(Charsets.UTF_8)),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val httpClient = HttpClient(mockEngine)
        val repository = SubsonicRepository(
            baseUrl = "http://mock.server.com",
            username = "mockuser",
            password = "mockpassword",
            httpClientOverride = httpClient
        )

        val rootFolderEntry = repository.getFolderContents(null)

        assertEquals("al-1", rootFolderEntry.id)
        assertEquals(".", rootFolderEntry.name)
        assertEquals(2, rootFolderEntry.entries.size)

        assertTrue(rootFolderEntry.entries[0] is MusicEntry)
        val firstItem = rootFolderEntry.entries[0] as MusicEntry
        assertEquals("folder-1", firstItem.id)
        assertEquals("Favorite_Artists.mp3", firstItem.name)
        assertEquals("/", firstItem.dir)

        assertTrue(rootFolderEntry.entries[1] is MusicEntry)
        val secondItem = rootFolderEntry.entries[1] as MusicEntry
        assertEquals("folder-2", secondItem.id)
        assertEquals("Cool_Mixtapes.opus", secondItem.name)
        assertEquals("/", secondItem.dir)

        repository.close()
    }

    @Test
    fun `getFolderContents for an empty folder should return empty list`() = runTest {
        val mockEmptyFolderJsonResponse = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "directory": {
                  "id": "10",
                  "name": "Empty Folder",
                  "parent": "some-parent-id", 
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
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
        val repository = SubsonicRepository(
            baseUrl = "http://mock.server.com",
            username = "mockuser",
            password = "mockpassword",
            httpClientOverride = httpClient
        )

        val folderEntry = repository.getFolderContents("folder-empty")

        assertEquals("10", folderEntry.id)
        assertEquals("Empty Folder", folderEntry.name)
        assertTrue(folderEntry.entries.isEmpty())

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

        val mockEngine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(mockAuthErrorJsonResponse.toByteArray(Charsets.UTF_8)),
                status = HttpStatusCode.OK, 
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        val repository = SubsonicRepository("http://mock.server.com", "u", "p", httpClient)

        try {
            repository.getFolderContents("folder-error-auth")
            fail("SubsonicApiException was expected but not thrown.")
        } catch (e: SubsonicApiException) {
            val expectedMessage = "Mocked: Incorrect username or password.. Raw response: '$mockAuthErrorJsonResponse'"
            assertEquals(expectedMessage, e.message)
            assertEquals(40, e.code)
        } finally {
            repository.close()
        }
    }

    @Test
    fun `getFolderContents with malformed JSON should throw SubsonicApiException`() = runTest {
        val malformedJsonResponse = """{"subsonic-response": {"status": "ok", "directory": {"id":"1" """ // Incomplete JSON

        val mockEngine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(malformedJsonResponse.toByteArray(Charsets.UTF_8)),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        val repository =
            SubsonicRepository("http://mock.server.com", "u", "p", httpClientOverride = httpClient)

        try {
            repository.getFolderContents("anyId")
            fail("SubsonicApiException was expected due to malformed JSON but not thrown.")
        } catch (e: SubsonicApiException) {
            val actualMessage = e.message ?: ""
            assertTrue("Message should indicate lenient parsing failure. Actual: $actualMessage", actualMessage.startsWith("Failed to deserialize the response body with lenient parsing."))
            assertTrue("Message should contain original error. Actual: $actualMessage", actualMessage.contains("Original deserialization error:"))
            assertTrue("Message should mention the specific JSON parsing error. Actual: $actualMessage", actualMessage.contains("Expected end of the object")) // Example, may vary
            assertTrue("Message should contain raw response. Actual: $actualMessage", actualMessage.contains("Raw response body: '$malformedJsonResponse'"))
            assertNull("Error code should be null for SerializationException wrapper", e.code)
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
                """.trimIndent()

            val mockEngine = MockEngine { _ ->
                respond(
                    content = ByteReadChannel(mockIncompleteDataResponse.toByteArray(Charsets.UTF_8)),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
            }
            val repository = SubsonicRepository("http://mock.server.com", "u", "p", httpClient)

            try {
                repository.getFolderContents("anyId")
                fail("SubsonicApiException was expected due to incomplete directory data but not thrown.")
            } catch (e: SubsonicApiException) {
                val expectedMessage = "directoryNode.id is null. Raw response: '$mockIncompleteDataResponse'"
                assertEquals(expectedMessage, e.message)
                assertNull("Error code should be null for this specific error type", e.code)
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

            val mockEngine = MockEngine { _ ->
                respond(
                    content = ByteReadChannel(unexpectedJsonResponse.toByteArray(Charsets.UTF_8)),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
            }
            val repository = SubsonicRepository("http://mock.server.com", "u", "p", httpClient)

            try {
                repository.getFolderContents("anyFolderId")
                fail("SubsonicApiException was expected due to completely unexpected JSON structure but not thrown.")
            } catch (e: SubsonicApiException) {
                val actualMessage = e.message ?: ""
                val expectedMessage = "Subsonic API reported failure (no specific error message in parsed response). Raw response: '$unexpectedJsonResponse'"
                assertEquals(expectedMessage, actualMessage)
                assertNull("Error code should be null", e.code)
            } finally {
                repository.close()
            }
        }

    @Test
    fun `getFolderContents with 200 OK but plain text response should throw SubsonicApiException`() =
        runTest {
            val plainTextResponse = "This is not JSON, this is plain text."

            val mockEngine = MockEngine { _ ->
                respond(
                    content = ByteReadChannel(plainTextResponse.toByteArray(Charsets.UTF_8)),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
            }
            val repository = SubsonicRepository("http://mock.server.com", "u", "p", httpClient)

            try {
                repository.getFolderContents("anyFolderId")
                fail("SubsonicApiException was expected due to plain text response but not thrown.")
            } catch (e: SubsonicApiException) {
                val actualMessage = e.message ?: ""
                assertTrue("Msg should indicate lenient parsing failure. Actual: $actualMessage", actualMessage.startsWith("Failed to deserialize the response body with lenient parsing."))
                assertTrue("Msg should contain original error about unexpected token. Actual: $actualMessage", actualMessage.contains("Original deserialization error:") && actualMessage.contains("Unexpected JSON token"))
                assertTrue("Msg should contain raw response. Actual: $actualMessage", actualMessage.contains("Raw response body: '$plainTextResponse'"))
                assertNull("Error code should be null", e.code)
            } finally {
                repository.close()
            }
        }
}
