package myhilmyhill.pureswr.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import myhilmyhill.pureswr.data.model.Credentials
import java.io.IOException

// Extension property to create the DataStore instance
private val Context.credentialsDataStore: DataStore<Preferences> by preferencesDataStore(name = "user_credentials_prefs")

class UserPreferencesRepository(private val context: Context) {

    private object PreferencesKeys {
        val BASE_URL = stringPreferencesKey("subsonic_base_url")
        val USERNAME = stringPreferencesKey("subsonic_username")
        val PASSWORD = stringPreferencesKey("subsonic_password")
    }

    val credentialsFlow: Flow<Credentials?> = context.credentialsDataStore.data
        .catch { exception ->
            // DataStore throws IOException when an error is encountered when reading data
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val baseUrl = preferences[PreferencesKeys.BASE_URL]
            val username = preferences[PreferencesKeys.USERNAME]
            val password = preferences[PreferencesKeys.PASSWORD]

            if (baseUrl != null && username != null && password != null) {
                Credentials(baseUrl, username, password)
            } else {
                null // Not all credentials are set
            }
        }
        .distinctUntilChanged() // Added this line

    suspend fun saveCredentials(credentials: Credentials) {
        context.credentialsDataStore.edit { preferences ->
            preferences[PreferencesKeys.BASE_URL] = credentials.baseUrl
            preferences[PreferencesKeys.USERNAME] = credentials.username
            preferences[PreferencesKeys.PASSWORD] = credentials.password
        }
    }

    suspend fun clearCredentials() {
        context.credentialsDataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.BASE_URL)
            preferences.remove(PreferencesKeys.USERNAME)
            preferences.remove(PreferencesKeys.PASSWORD)
        }
    }
}
