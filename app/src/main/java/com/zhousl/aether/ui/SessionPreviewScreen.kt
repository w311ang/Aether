package com.zhousl.aether.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhousl.aether.R
import com.zhousl.aether.ui.theme.AetherBackground
import com.zhousl.aether.ui.theme.AetherBackgroundGradientTop
import com.zhousl.aether.ui.theme.AetherOnSurface
import com.zhousl.aether.ui.theme.AetherOnSurfaceVariant
import com.zhousl.aether.ui.theme.AetherSurface
import kotlinx.coroutines.launch

/** What the preview screen shows; produced by `SessionPreviewActivity`. */
internal sealed interface SessionPreviewUiState {
    data object Loading : SessionPreviewUiState

    data class Failed(val message: String) : SessionPreviewUiState

    /** The sender passed a file:// path that Aether can only read with storage permission. */
    data object NeedsStoragePermission : SessionPreviewUiState

    data class Loaded(
        val session: ChatSession,
        val sourceName: String,
        val isSaving: Boolean = false,
        val savedSessionId: String? = null,
    ) : SessionPreviewUiState
}

@Composable
internal fun SessionPreviewScreen(
    state: SessionPreviewUiState,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onOpenSavedChat: () -> Unit,
    onGrantStoragePermission: () -> Unit,
    onOpenLink: (String) -> Unit,
    onCopyMessage: (ChatMessage) -> Unit,
    onUnavailableAction: () -> Unit,
) {
    val loaded = state as? SessionPreviewUiState.Loaded
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = AetherBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SessionPreviewTopBar(
                title = loaded?.session?.title?.ifBlank { null }
                    ?: stringResource(R.string.session_preview_title),
                subtitle = loaded?.let {
                    stringResource(
                        R.string.session_preview_subtitle,
                        it.session.messages.size,
                        it.sourceName,
                    )
                }.orEmpty(),
                isSaving = loaded?.isSaving == true,
                isSaved = loaded?.savedSessionId != null,
                showSaveAction = loaded != null,
                onClose = onClose,
                onSave = onSave,
                onOpenSavedChat = onOpenSavedChat,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(AetherBackgroundGradientTop, AetherBackground, AetherSurface)
                    )
                )
                .padding(innerPadding)
        ) {
            when (state) {
                SessionPreviewUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                is SessionPreviewUiState.Failed -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = AetherOnSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 32.dp),
                    )
                }

                SessionPreviewUiState.NeedsStoragePermission -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.session_preview_storage_permission_needed),
                            style = MaterialTheme.typography.bodyLarge,
                            color = AetherOnSurfaceVariant,
                        )
                        Button(onClick = onGrantStoragePermission) {
                            Text(stringResource(R.string.session_preview_grant_storage))
                        }
                    }
                }

                is SessionPreviewUiState.Loaded -> {
                    SessionPreviewConversation(
                        session = state.session,
                        onOpenLink = onOpenLink,
                        onCopyMessage = onCopyMessage,
                        onUnavailableAction = onUnavailableAction,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionPreviewTopBar(
    title: String,
    subtitle: String,
    isSaving: Boolean,
    isSaved: Boolean,
    showSaveAction: Boolean,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onOpenSavedChat: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AetherSurface)
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Rounded.Close, stringResource(R.string.common_close))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = AetherOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = AetherOnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showSaveAction) {
            if (isSaved) {
                TextButton(onClick = onOpenSavedChat) {
                    Text(stringResource(R.string.session_preview_open_saved))
                }
            } else {
                TextButton(onClick = onSave, enabled = !isSaving) {
                    Text(stringResource(R.string.session_preview_save))
                }
            }
        }
    }
}

@Composable
private fun SessionPreviewConversation(
    session: ChatSession,
    onOpenLink: (String) -> Unit,
    onCopyMessage: (ChatMessage) -> Unit,
    onUnavailableAction: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var previewAttachment by remember { mutableStateOf<ChatAttachment?>(null) }
    val conversationItems = remember(session.messages) { buildConversationListItems(session.messages) }
    val timelineTargets = remember(conversationItems) {
        buildConversationTimelineTargets(conversationItems)
    }
    val currentTimelineIndex = rememberConversationTimelineIndex(listState, timelineTargets)
    val sessionTotalTokens = remember(session.messages) {
        session.messages.sumOf { message -> message.usageStatistics?.totalTokens ?: 0L }
            .takeIf { it > 0L }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        StreamingRevealHost(conversationKey = session.id) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                // Timeline targets index past one leading item, matching ConversationScreen.
                item(key = "session-preview-list-start") {
                    Spacer(modifier = Modifier.fillMaxWidth())
                }
                items(conversationItems, key = { it.key }) { item ->
                    when (item) {
                        is ConversationListItem.Message -> {
                            val message = item.message
                            ConversationMessageBubble(
                                message = message,
                                actionsEnabled = true,
                                workspaceDirectory = null,
                                onOpenAttachment = { previewAttachment = it },
                                onOpenLink = onOpenLink,
                                onEdit = onUnavailableAction,
                                onDelete = onUnavailableAction,
                                onCopy = { onCopyMessage(message) },
                                onRedo = onUnavailableAction,
                                onRetry = onUnavailableAction,
                                onSwitchBranch = { onUnavailableAction() },
                                sessionTotalTokens = sessionTotalTokens,
                            )
                        }

                        is ConversationListItem.AssistantGroup -> {
                            val lastMessage = item.messages.last()
                            ConversationAssistantGroupBubble(
                                messages = item.messages,
                                actionsEnabled = true,
                                workspaceDirectory = null,
                                onOpenAttachment = { previewAttachment = it },
                                onOpenLink = onOpenLink,
                                onCopy = {
                                    onCopyMessage(
                                        lastMessage.copy(
                                            text = item.messages.joinToString("\n\n") { message -> message.text }
                                                .trim(),
                                        )
                                    )
                                },
                                onRedo = onUnavailableAction,
                                onDelete = onUnavailableAction,
                                sessionTotalTokens = sessionTotalTokens,
                            )
                        }

                        is ConversationListItem.CompactStatus -> {
                            CompactStatusDivider(
                                text = item.message.text.ifBlank { stringResource(R.string.chat_context_compacted) },
                            )
                        }
                    }
                }
                item(key = "session-preview-bottom-anchor") {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .navigationBarsPadding()
                    )
                }
            }
        }

        ConversationTimeline(
            entries = timelineTargets.map(ConversationTimelineTarget::entry),
            currentIndex = currentTimelineIndex,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 8.dp, bottom = 30.dp)
                .navigationBarsPadding(),
            onNavigate = { timelineIndex ->
                timelineTargets.getOrNull(timelineIndex)?.let { target ->
                    scope.launch {
                        listState.scrollToItem(target.listItemIndex)
                    }
                }
            },
        )

        previewAttachment?.let { attachment ->
            AttachmentPreviewDialog(
                attachment = attachment,
                onDismiss = { previewAttachment = null },
                onSave = onUnavailableAction,
            )
        }
    }
}
