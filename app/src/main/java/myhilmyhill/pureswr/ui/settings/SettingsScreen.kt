package myhilmyhill.pureswr.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
// import androidx.compose.runtime.remember // Not used currently
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
// import androidx.compose.ui.platform.LocalContext // Not used currently
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
// import androidx.lifecycle.viewmodel.compose.viewModel // ViewModel is passed directly
import myhilmyhill.pureswr.data.model.Credentials // Import Credentials
import myhilmyhill.pureswr.data.preferences.UserPreferencesRepository

// Simple ViewModel Factory for SettingsViewModel
class SettingsViewModelFactory(private val userPreferencesRepository: UserPreferencesRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(userPreferencesRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel, // ViewModel is passed directly
    onCredentialsSaved: (Credentials) -> Unit // MODIFIED: Pass Credentials object back
) {
    val uiState = settingsViewModel.uiState
    // val context = LocalContext.current // Not used currently, can be re-added if needed

    // If a save message appears and it indicates success, call onCredentialsSaved
    LaunchedEffect(uiState.saveMessage) {
        if (uiState.saveMessage == "Credentials saved successfully!") {
            // Construct Credentials from the current uiState, which should reflect the saved values
            val savedCredentials = Credentials(
                baseUrl = uiState.baseUrl,
                username = uiState.username,
                password = uiState.password
            )
            onCredentialsSaved(savedCredentials) // MODIFIED: Pass the credentials
            settingsViewModel.clearSaveMessage() // Clear the message after consuming it
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Server Settings") })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (uiState.isLoading && uiState.baseUrl.isEmpty()) { // Show loading only on initial load
                CircularProgressIndicator()
                Text("Loading saved settings...")
            } else {
                OutlinedTextField(
                    value = uiState.baseUrl,
                    onValueChange = { settingsViewModel.onBaseUrlChanged(it) },
                    label = { Text("Server Base URL (e.g., http://your.server.com:port)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = uiState.username,
                    onValueChange = { settingsViewModel.onUsernameChanged(it) },
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = uiState.password,
                    onValueChange = { settingsViewModel.onPasswordChanged(it) },
                    label = { Text("Password") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )

                Button(
                    onClick = { settingsViewModel.saveCredentials() },
                    enabled = !uiState.isLoading,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Save Credentials")
                }

                uiState.saveMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }
                uiState.errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
