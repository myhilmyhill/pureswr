package myhilmyhill.pureswr.ui.folder

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel // Added Hilt import
import javax.inject.Inject // Added Inject import
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import myhilmyhill.pureswr.data.model.Credentials
import myhilmyhill.pureswr.data.preferences.UserPreferencesRepository
import myhilmyhill.pureswr.data.repository.FolderEntry
import myhilmyhill.pureswr.data.repository.SubsonicRepository
import myhilmyhill.pureswr.data.repository.SubsonicApiException

data class FolderUiState(
    val isLoading: Boolean = false,
    val folder: FolderEntry? = null,
    val error: String? = null,
    val needsConfiguration: Boolean = false
)

@HiltViewModel // Added HiltViewModel annotation
class FolderViewModel @Inject constructor( // Added Inject annotation
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FolderUiState())
    val uiState: StateFlow<FolderUiState> = _uiState.asStateFlow()

    private var currentSubsonicRepository: SubsonicRepository? = null
    private var lastAttemptedFolderId: String? = null 

    init {
        Log.d("FolderViewModel", "Initializing...")
        viewModelScope.launch {
            userPreferencesRepository.credentialsFlow.collectLatest { credentials ->
                Log.d("FolderViewModel", "Credentials updated: ${credentials?.username}")

                currentSubsonicRepository?.close()
                currentSubsonicRepository = null

                if (credentials != null && credentials.baseUrl.isNotBlank() && credentials.username.isNotBlank()) {
                    Log.d("FolderViewModel", "Creating new SubsonicRepository instance.")
                    currentSubsonicRepository = SubsonicRepository(
                        baseUrl = credentials.baseUrl,
                        username = credentials.username,
                        password = credentials.password
                    )
                    Log.d("FolderViewModel", "New SubsonicRepository instance created: ${System.identityHashCode(currentSubsonicRepository)}")
                    if (_uiState.value.folder != null || lastAttemptedFolderId != null || _uiState.value.error != null || _uiState.value.needsConfiguration) {
                         _uiState.value = _uiState.value.copy(needsConfiguration = false)
                        loadFolder(lastAttemptedFolderId)
                    }
                } else {
                    Log.w("FolderViewModel", "Credentials are null or incomplete. SubsonicRepository not created.")
                    _uiState.value = FolderUiState(error = "Server not configured. Please set credentials in Settings.", needsConfiguration = true)
                }
            }
        }
    }

    fun loadFolder(folderId: String? = null) {
        lastAttemptedFolderId = folderId
        Log.d("FolderViewModel", "loadFolder called for ID: $folderId. Using SubsonicRepository: ${System.identityHashCode(currentSubsonicRepository)}")

        val repository = currentSubsonicRepository
        if (repository == null) {
            Log.w("FolderViewModel", "loadFolder: SubsonicRepository is null. Credentials might be missing or invalid.")
             if (!_uiState.value.needsConfiguration) {
                 _uiState.value = FolderUiState(error = "Subsonic client not initialized. Check credentials.", needsConfiguration = true)
             }
            return
        }

        viewModelScope.launch {
            _uiState.value = FolderUiState(isLoading = true)
            try {
                val folderContents = repository.getFolderContents(folderId)
                _uiState.value = FolderUiState(folder = folderContents)
            } catch (e: SubsonicApiException) {
                _uiState.value = FolderUiState(error = "API Error: ${e.message} (Code: ${e.code ?: "N/A"})")
            } catch (e: Exception) {
                _uiState.value = FolderUiState(error = "An unexpected error occurred: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        Log.d("FolderViewModel", "onCleared called. Closing SubsonicRepository.")
        currentSubsonicRepository?.close()
        super.onCleared()
    }
}
