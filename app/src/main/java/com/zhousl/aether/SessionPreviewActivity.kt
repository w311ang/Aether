package com.zhousl.aether

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.zhousl.aether.data.AppLanguage
import com.zhousl.aether.data.AppSettings
import com.zhousl.aether.data.AppThemeMode
import com.zhousl.aether.data.SessionPreviewParseResult
import com.zhousl.aether.data.asImportedSession
import com.zhousl.aether.data.isDocumentInTree
import com.zhousl.aether.data.isRestrictedTreeDocumentId
import com.zhousl.aether.data.parentDocumentId
import com.zhousl.aether.data.parseSessionPreviewFile
import com.zhousl.aether.data.primaryStorageDocumentId
import com.zhousl.aether.ui.ChatMessage
import com.zhousl.aether.ui.SessionPreviewScreen
import com.zhousl.aether.ui.SessionPreviewUiState
import com.zhousl.aether.ui.theme.AetherTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opens an exported chat (`exportType: "session"`) from another app as a read-only conversation. */
class SessionPreviewActivity : AppCompatActivity() {
    private val viewModel: SessionPreviewViewModel by viewModels()
    private val folderAccessLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri != null) viewModel.onFolderGranted(treeUri)
    }
    private val pickFileLauncher = registerForActivityResult(OpenDocumentInFolder()) { uri ->
        if (uri != null) viewModel.open(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            viewModel.open(intent.sessionPreviewUri())
        }
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            AetherTheme(
                themeMode = settings?.themeMode ?: AppThemeMode.System,
                language = settings?.language ?: AppLanguage.English,
            ) {
                SessionPreviewScreen(
                    state = state,
                    onClose = ::finish,
                    onSave = viewModel::saveToChats,
                    onOpenSavedChat = ::openSavedChat,
                    onGrantFolderAccess = { folderAccessLauncher.launch(viewModel.pendingFolderUri) },
                    onPickFile = { pickFileLauncher.launch(viewModel.pendingFolderUri) },
                    onOpenLink = ::openLink,
                    onCopyMessage = ::copyMessage,
                    onUnavailableAction = { showToast(getString(R.string.session_preview_action_unavailable)) },
                )
            }
        }
        lifecycleScope.launch {
            viewModel.messages.collect { message -> showToast(message) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.open(intent.sessionPreviewUri())
    }

    private fun openSavedChat() {
        // The saved chat is already the current session, so the main screen opens on it.
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    private fun openLink(rawLink: String) {
        val uri = Uri.parse(rawLink.trim())
        if (uri.scheme != "http" && uri.scheme != "https") {
            showToast(getString(R.string.session_preview_action_unavailable))
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            showToast(getString(R.string.app_unable_to_open_link))
        }
    }

    private fun copyMessage(message: ChatMessage) {
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("message", message.text))
        showToast(getString(R.string.file_reply_copied))
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

private fun Intent.sessionPreviewUri(): Uri? = when (action) {
    Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
    else -> data
}

private const val ExternalStorageAuthority = "com.android.externalstorage.documents"

/** ACTION_OPEN_DOCUMENT that starts in the given folder, so the user only has to tap the file. */
private class OpenDocumentInFolder : ActivityResultContract<Uri?, Uri?>() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .apply { if (input != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, input) }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent.takeIf { resultCode == Activity.RESULT_OK }?.data
}

internal class SessionPreviewViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = (application as AetherApplication).runtime
    private val _state = MutableStateFlow<SessionPreviewUiState>(SessionPreviewUiState.Loading)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    val state: StateFlow<SessionPreviewUiState> = _state.asStateFlow()
    val messages: SharedFlow<String> = _messages
    val settings: StateFlow<AppSettings?> = runtime.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var currentUri: Uri? = null

    /** Folder of the last unreadable file:// path; the system pickers start there. */
    var pendingFolderUri: Uri? = null
        private set

    fun onFolderGranted(treeUri: Uri) {
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val documentId = currentUri?.path?.let(::primaryStorageDocumentId)
        val coversCurrentFile = documentId != null &&
            treeUri.authority == ExternalStorageAuthority &&
            isDocumentInTree(documentId, DocumentsContract.getTreeDocumentId(treeUri))
        if (!coversCurrentFile) {
            _messages.tryEmit(app.getString(R.string.session_preview_folder_not_covering))
        }
        open(currentUri)
    }

    fun open(uri: Uri?) {
        val app = getApplication<Application>()
        currentUri = uri
        if (uri == null) {
            _state.value = SessionPreviewUiState.Failed(app.getString(R.string.session_preview_no_file))
            return
        }
        _state.value = SessionPreviewUiState.Loading
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) {
                val readableUri = grantedFolderDocumentUri(uri) ?: uri
                val sourceName = displayName(readableUri)
                val rawValue = runCatching {
                    app.contentResolver.openInputStream(readableUri)?.use { stream ->
                        stream.bufferedReader(Charsets.UTF_8).readText()
                    }
                }.getOrElse { throwable ->
                    if (readableUri.scheme == ContentResolver.SCHEME_FILE) {
                        fileAccessState(readableUri)?.let { return@withContext it }
                    }
                    return@withContext SessionPreviewUiState.Failed(
                        app.getString(
                            R.string.session_preview_read_failed,
                            throwable.message ?: throwable.javaClass.simpleName,
                        )
                    )
                } ?: return@withContext SessionPreviewUiState.Failed(
                    app.getString(R.string.session_preview_read_failed, sourceName)
                )
                when (val result = parseSessionPreviewFile(rawValue)) {
                    is SessionPreviewParseResult.Success -> SessionPreviewUiState.Loaded(
                        session = result.session,
                        sourceName = sourceName,
                    )

                    is SessionPreviewParseResult.UnsupportedExportType -> SessionPreviewUiState.Failed(
                        app.getString(
                            R.string.session_preview_unsupported,
                            result.exportType.ifBlank { "?" },
                        )
                    )

                    SessionPreviewParseResult.Invalid -> SessionPreviewUiState.Failed(
                        app.getString(R.string.session_preview_invalid)
                    )
                }
            }
        }
    }

    fun saveToChats() {
        val loaded = _state.value as? SessionPreviewUiState.Loaded ?: return
        if (loaded.isSaving || loaded.savedSessionId != null) return
        _state.value = loaded.copy(isSaving = true)
        val app = getApplication<Application>()
        viewModelScope.launch {
            val imported = loaded.session.asImportedSession(System.currentTimeMillis())
            val result = runCatching {
                // Making the imported chat current keeps the previously current chat out of the
                // message sync, so its stored messages are never rewritten from this snapshot.
                runtime.chatStateStore.updateAndFlush { persisted ->
                    persisted.copy(
                        sessions = listOf(imported) + persisted.sessions,
                        currentSessionId = imported.id,
                    )
                }
            }
            _state.update { current ->
                (current as? SessionPreviewUiState.Loaded)?.copy(
                    isSaving = false,
                    savedSessionId = imported.id.takeIf { result.isSuccess },
                ) ?: current
            }
            _messages.tryEmit(
                result.fold(
                    onSuccess = { app.getString(R.string.session_preview_saved) },
                    onFailure = { throwable ->
                        app.getString(
                            R.string.session_preview_save_failed,
                            throwable.message ?: throwable.javaClass.simpleName,
                        )
                    },
                )
            )
        }
    }

    /**
     * Apps targeting SDK 28 (like Aether) receive file:// paths from file managers such as MT.
     * Without storage permission those are readable only through a folder the user granted in the
     * system picker, so look the path up in the persisted folder grants.
     */
    private fun grantedFolderDocumentUri(uri: Uri): Uri? {
        if (uri.scheme != ContentResolver.SCHEME_FILE) return null
        val documentId = uri.path?.let(::primaryStorageDocumentId) ?: return null
        return getApplication<Application>().contentResolver.persistedUriPermissions
            .asSequence()
            .map { it.uri }
            .filter { it.authority == ExternalStorageAuthority && DocumentsContract.isTreeUri(it) }
            .firstOrNull { isDocumentInTree(documentId, DocumentsContract.getTreeDocumentId(it)) }
            ?.let { DocumentsContract.buildDocumentUriUsingTree(it, documentId) }
    }

    private fun fileAccessState(uri: Uri): SessionPreviewUiState.NeedsFileAccess? {
        val documentId = uri.path?.let(::primaryStorageDocumentId) ?: return null
        val folderId = parentDocumentId(documentId) ?: return null
        pendingFolderUri = DocumentsContract.buildDocumentUri(ExternalStorageAuthority, folderId)
        return SessionPreviewUiState.NeedsFileAccess(
            folderName = folderId.substringAfter(':').ifBlank { "/" },
            canGrantFolder = !isRestrictedTreeDocumentId(folderId, Build.VERSION.SDK_INT),
        )
    }

    private fun displayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        val queried = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return queried?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty()
    }
}
