package com.zhousl.aether

import android.Manifest
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
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
import com.zhousl.aether.data.parseSessionPreviewFile
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
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.reopen()
        } else {
            showToast(getString(R.string.session_preview_storage_permission_denied))
        }
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
                    onGrantStoragePermission = {
                        storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                    },
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

internal class SessionPreviewViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = (application as AetherApplication).runtime
    private val _state = MutableStateFlow<SessionPreviewUiState>(SessionPreviewUiState.Loading)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    val state: StateFlow<SessionPreviewUiState> = _state.asStateFlow()
    val messages: SharedFlow<String> = _messages
    val settings: StateFlow<AppSettings?> = runtime.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var currentUri: Uri? = null

    fun reopen() {
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
                val sourceName = displayName(uri)
                val rawValue = runCatching {
                    app.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader(Charsets.UTF_8).readText()
                    }
                }.getOrElse { throwable ->
                    if (uri.scheme == ContentResolver.SCHEME_FILE && !hasStoragePermission()) {
                        return@withContext SessionPreviewUiState.NeedsStoragePermission
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

    private fun hasStoragePermission(): Boolean = ContextCompat.checkSelfPermission(
        getApplication(),
        Manifest.permission.READ_EXTERNAL_STORAGE,
    ) == PackageManager.PERMISSION_GRANTED

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
