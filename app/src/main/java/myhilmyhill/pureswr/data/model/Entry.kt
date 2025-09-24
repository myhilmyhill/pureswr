package myhilmyhill.pureswr.data.model

import kotlinx.serialization.Serializable

@Serializable
sealed interface Entry {
    val id: String
    val name: String
}

@Serializable
data class FolderEntry(
    override val id: String,
    override val name: String,
    val entries: List<Entry>,
    val parentFolderId: String? = null
) : Entry

@Serializable
data class MusicEntry(
    override val id: String,
    override val name: String,
    val dir: String,
    val parentFolderId: String? = null
) : Entry
