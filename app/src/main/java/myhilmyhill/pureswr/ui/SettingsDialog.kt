package myhilmyhill.pureswr.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import myhilmyhill.pureswr.data.model.Credentials // Make sure this path is correct
import myhilmyhill.pureswr.ui.theme.PureswrTheme

@Composable
fun SettingsDialog(
    onDismissRequest: () -> Unit,
    currentBaseUrl: String,
    currentUsername: String,
    currentPassword: String,
    onSave: (Credentials) -> Unit
) {
    var baseUrl by remember { mutableStateOf(currentBaseUrl) }
    var username by remember { mutableStateOf(currentUsername) }
    var password by remember { mutableStateOf(currentPassword) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Subsonic Server Settings") },
        text = {
            Column {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Server Base URL (e.g., http://your.server.com)") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true
                    // Consider using PasswordVisualTransformation for password field
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val newCredentials = Credentials(baseUrl.trim(), username.trim(), password) // password usually not trimmed
                    onSave(newCredentials)
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancel")
            }
        }
    )
}

@Preview(showBackground = true)
@Composable
fun SettingsDialogPreview() {
    PureswrTheme {
        SettingsDialog(
            onDismissRequest = {},
            currentBaseUrl = "http://example.com",
            currentUsername = "user",
            currentPassword = "pass",
            onSave = {}
        )
    }
}
