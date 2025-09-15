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

    // Test for root folder contents (remains unchanged as it's a success case)
    @Test
    fun `getFolderContents for root should return parsed list from mock response`() = runTest {
        val mockRootFolderJsonResponse = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "musicFolders": {
                  "musicFolder": [
                    { "id": "folder-1", "name": "Favorite Artists" },
                    { "id": "folder-2", "name": "Cool Mixtapes" }
                  ]
                }
              }
            }
            """.trimIndent()

        val mockEngine = MockEngine { request ->
            assertEquals("URL path mismatch for root request", "/rest/getMusicFolders.view", request.url.encodedPath)
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

        val rootFolderEntry = repository.getFolderContents(null) // Requesting root

        assertEquals(SubsonicRepository.SYNTHETIC_ROOT_ID, rootFolderEntry.id)
        assertEquals(SubsonicRepository.SYNTHETIC_ROOT_NAME, rootFolderEntry.name)
        assertEquals(2, rootFolderEntry.entries.size)

        val firstItem = rootFolderEntry.entries[0]
        assertTrue(firstItem is FolderEntry)
        assertEquals("folder-1", (firstItem as FolderEntry).id)
        assertEquals("Favorite Artists", firstItem.name)

        repository.close()
    }

    // Test for empty folder (remains unchanged as it's a success case)
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
                status = HttpStatusCode.OK, // Server might respond 200 OK but with error in JSON body
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
            val expectedMessage = "Mocked: Incorrect username or password.. Raw response: '${mockAuthErrorJsonResponse}'"
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
        // Repository uses its own lenientJsonParser, so HttpClient's parser config isn't the primary one tested here for this specific internal parsing step.
        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) } // Aligned with repo's general approach
        }
        val repository =
            SubsonicRepository("http://mock.server.com", "u", "p", httpClientOverride = httpClient)

        try {
            repository.getFolderContents("anyId")
            fail("SubsonicApiException was expected due to malformed JSON but not thrown.")
        } catch (e: SubsonicApiException) {
            val actualMessage = e.message ?: ""
            assertTrue("Message should indicate lenient parsing failure. Actual: $actualMessage", actualMessage.contains("failed to deserialize the response body with lenient parsing"))
            assertTrue("Message should contain original error. Actual: $actualMessage", actualMessage.contains("Original deserialization error:"))
            assertTrue("Message should mention EOF or similar. Actual: $actualMessage", actualMessage.contains("Expected") && actualMessage.contains("EOF"))
            assertTrue("Message should contain raw response. Actual: $actualMessage", actualMessage.contains("Raw response body: '${malformedJsonResponse}'"))
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
                val expectedMessage = "Incomplete directory data for folderId 'anyId': ID or Name from server is null. Raw response: '${mockIncompleteDataResponse}'"
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
                // The SubsonicResponse DTO has subsonicResponse field as nullable.
                // Parsing the unexpectedJsonResponse will result in a SubsonicResponse object where the subsonicResponse field is null.
                // This then leads to the "Subsonic API reported failure..." path in the repository code.
                val expectedMessage = "Subsonic API reported failure (no specific error message in parsed response). Raw response: '${unexpectedJsonResponse}'"
                assertEquals(expectedMessage, e.message)
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
                assertTrue("Msg should indicate lenient parsing failure. Actual: $actualMessage", actualMessage.contains("failed to deserialize the response body with lenient parsing"))
                assertTrue("Msg should contain original error about unexpected token. Actual: $actualMessage", actualMessage.contains("Original deserialization error:") && actualMessage.contains("Unexpected JSON token"))
                assertTrue("Msg should contain raw response. Actual: $actualMessage", actualMessage.contains("Raw response body: '${plainTextResponse}'"))
                assertNull("Error code should be null", e.code)
            } finally {
                repository.close()
            }
        }
}
