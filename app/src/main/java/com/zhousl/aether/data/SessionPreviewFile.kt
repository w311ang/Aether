package com.zhousl.aether.data

import com.zhousl.aether.ui.ChatSession
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal const val SessionExportType = "session"

internal sealed interface SessionPreviewParseResult {
    data class Success(val session: ChatSession) : SessionPreviewParseResult

    data class UnsupportedExportType(val exportType: String) : SessionPreviewParseResult

    data object Invalid : SessionPreviewParseResult
}

/**
 * Parses a single-chat export (`exportType: "session"`, as written by "Export chat")
 * for read-only preview. The embedded pi session is ignored: a preview never resumes the agent.
 */
internal fun parseSessionPreviewFile(rawValue: String): SessionPreviewParseResult {
    val root = try {
        JSONObject(rawValue.removePrefix("﻿"))
    } catch (_: JSONException) {
        return SessionPreviewParseResult.Invalid
    }
    val exportType = root.optString("exportType")
    if (exportType != SessionExportType) {
        return SessionPreviewParseResult.UnsupportedExportType(exportType)
    }
    val sessionJson = root.optJSONObject("session") ?: return SessionPreviewParseResult.Invalid
    val parsed = parseChatSessionsForMigration(JSONArray().put(sessionJson).toString())
    // A recovered result is a placeholder for corrupt storage, not the file's conversation.
    if (parsed.recoveredFromCorruption) return SessionPreviewParseResult.Invalid
    val session = parsed.sessions.singleOrNull() ?: return SessionPreviewParseResult.Invalid
    if (session.messages.isEmpty()) return SessionPreviewParseResult.Invalid
    return SessionPreviewParseResult.Success(session)
}

private val PrimaryStoragePathPrefix = Regex("^(/storage/emulated/\\d+|/sdcard|/storage/self/primary)/")

/**
 * Maps a path on primary shared storage to its ExternalStorageProvider document id, e.g.
 * `/storage/emulated/0/Download/a.json` -> `primary:Download/a.json`. File managers such as MT pass
 * such paths to apps targeting SDK 28; the id lets a folder the user granted through the system
 * picker read them without any storage permission.
 */
internal fun primaryStorageDocumentId(path: String): String? {
    val prefix = PrimaryStoragePathPrefix.find(path) ?: return null
    val relativePath = path.substring(prefix.range.last + 1).trim('/')
    if (relativePath.isEmpty()) return null
    return "primary:$relativePath"
}

/** The id of the folder that holds [documentId], or null for a document at the volume root. */
internal fun parentDocumentId(documentId: String): String? {
    val separator = documentId.lastIndexOf('/')
    if (separator >= 0) return documentId.substring(0, separator)
    val volumeSeparator = documentId.indexOf(':')
    return if (volumeSeparator >= 0) documentId.substring(0, volumeSeparator + 1) else null
}

internal fun isDocumentInTree(documentId: String, treeDocumentId: String): Boolean = when {
    documentId == treeDocumentId -> true
    treeDocumentId.endsWith(':') -> documentId.startsWith(treeDocumentId)
    else -> documentId.startsWith("$treeDocumentId/")
}

/**
 * Android 11+ refuses to grant the storage root, Download itself, and Android/data or Android/obb
 * through the folder picker, so a file directly in those can only be picked one at a time.
 */
internal fun isRestrictedTreeDocumentId(treeDocumentId: String, sdkInt: Int): Boolean {
    if (sdkInt < 30) return false
    val relativePath = treeDocumentId.substringAfter(':', missingDelimiterValue = treeDocumentId).trim('/')
    return relativePath.isEmpty() ||
        relativePath.equals("Download", ignoreCase = true) ||
        relativePath.equals("Android/data", ignoreCase = true) ||
        relativePath.startsWith("Android/data/", ignoreCase = true) ||
        relativePath.equals("Android/obb", ignoreCase = true) ||
        relativePath.startsWith("Android/obb/", ignoreCase = true)
}

/**
 * A copy of a previewed session that can be added to the chat list. It always gets a fresh id so
 * re-importing Aether's own export never overwrites the original chat.
 */
internal fun ChatSession.asImportedSession(nowMillis: Long): ChatSession = copy(
    id = "session-$nowMillis",
    selectedSkillIds = emptyList(),
    activeSkills = emptyList(),
    activeMcpServerIds = emptyList(),
    agentModeEnabled = false,
    chromeEnabled = false,
)
