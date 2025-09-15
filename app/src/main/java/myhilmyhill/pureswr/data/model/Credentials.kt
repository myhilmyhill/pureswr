package myhilmyhill.pureswr.data.model

/**
 * Data class to hold Subsonic server credentials.
 */
data class Credentials(
    val baseUrl: String,
    val username: String,
    val password: String
)
