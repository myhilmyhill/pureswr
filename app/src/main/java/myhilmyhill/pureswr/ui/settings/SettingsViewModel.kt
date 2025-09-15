package myhilmyhill.pureswr.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import myhilmyhill.pureswr.data.model.Credentials
import myhilmyhill.pureswr.data.preferences.UserPreferencesRepository

data class SettingsUiState(
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val saveMessage: String? = null,
    val errorMessage: String? = null
)

class SettingsViewModel(private val userPreferencesRepository: UserPreferencesRepository) : ViewModel() {

    var uiState by mutableStateOf(SettingsUiState())
        private set

    init {
        loadCredentials()
    }

    private fun loadCredentials() {
        viewModelScope.launch {
            val currentErrorMessage = uiState.errorMessage
            uiState = uiState.copy(isLoading = true, saveMessage = null, errorMessage = null)
            try {
                val credentials = userPreferencesRepository.credentialsFlow.first()
                if (credentials != null) {
                    uiState = uiState.copy(
                        baseUrl = credentials.baseUrl,
                        username = credentials.username,
                        password = credentials.password,
                        isLoading = false,
                        errorMessage = currentErrorMessage
                    )
                } else {
                    uiState = uiState.copy(
                        baseUrl = "",
                        username = "",
                        password = "",
                        isLoading = false,
                        errorMessage = currentErrorMessage
                    )
                }
            } catch (e: Exception) {
                uiState = uiState.copy(
                    baseUrl = "",
                    username = "",
                    password = "",
                    isLoading = false,
                    errorMessage = "Failed to load credentials: ${e.message}"
                )
            }
        }
    }

    fun onBaseUrlChanged(baseUrl: String) {
        uiState = uiState.copy(baseUrl = baseUrl, saveMessage = null, errorMessage = null)
    }

    fun onUsernameChanged(username: String) {
        uiState = uiState.copy(username = username, saveMessage = null, errorMessage = null)
    }

    fun onPasswordChanged(password: String) {
        uiState = uiState.copy(password = password, saveMessage = null, errorMessage = null)
    }

    fun saveCredentials() {
        if (uiState.baseUrl.isBlank() || uiState.username.isBlank()) {
            uiState = uiState.copy(errorMessage = "Base URL and Username cannot be empty.")
            return
        }

        viewModelScope.launch {
            uiState = uiState.copy(isLoading = true, errorMessage = null, saveMessage = null)
            // These are the values from the UI, including the potentially changed username
            val credentialsToSave = Credentials(
                baseUrl = uiState.baseUrl.trim(),
                username = uiState.username.trim(),
                password = uiState.password
            )
            try {
                // Step 1: Attempt to save to DataStore
                userPreferencesRepository.saveCredentials(credentialsToSave)

                // Step 2: If saveCredentials did not throw an exception, assume it was successful.
                // Update the UI state directly with the values that were intended to be saved.
                // This ensures the UI reflects the (new) username immediately.
                uiState = uiState.copy(
                    baseUrl = credentialsToSave.baseUrl,
                    username = credentialsToSave.username, // Use the username from credentialsToSave
                    password = credentialsToSave.password,
                    isLoading = false,
                    saveMessage = "Credentials saved successfully!"
                )
            } catch (e: Exception) {
                // If saving failed, update UI with an error message.
                // Keep the user's input in the fields so they can retry or correct.
                uiState = uiState.copy(
                    isLoading = false,
                    errorMessage = "Failed to save credentials: ${e.message}",
                    baseUrl = credentialsToSave.baseUrl, 
                    username = credentialsToSave.username,
                    password = credentialsToSave.password
                )
            }
        }
    }

    fun clearSaveMessage() {
        uiState = uiState.copy(saveMessage = null)
    }
}
