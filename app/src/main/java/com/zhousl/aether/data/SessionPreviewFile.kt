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
