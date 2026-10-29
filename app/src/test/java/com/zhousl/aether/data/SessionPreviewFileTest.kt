package com.zhousl.aether.data

import com.zhousl.aether.ui.ChatMessage
import com.zhousl.aether.ui.ChatSession
import com.zhousl.aether.ui.MessageAuthor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPreviewFileTest {
    private val session = ChatSession(
        id = "session-1793212521820",
        title = "Imported chat",
        preview = "hello",
        hasCustomTitle = true,
        agentModeEnabled = true,
        messages = listOf(
            ChatMessage(
                id = "user-1793212521820",
                author = MessageAuthor.User,
                text = "hello",
                createdAtMillis = 1_793_212_521_820L,
            ),
            ChatMessage(
                id = "agent-1",
                author = MessageAuthor.Agent,
                text = "hi there",
                createdAtMillis = 1_793_212_524_071L,
                responseGroupId = "agent-group-1",
            ),
        ),
    )

    // Mirrors AetherViewModel.exportSessionToUri.
    private fun sessionExport(exportType: String = SessionExportType): String = JSONObject().apply {
        put("schemaVersion", 1)
        put("exportType", exportType)
        put("exportedAtMillis", 1_793_258_605_415L)
        put("session", session.toJson())
        put("piSession", JSONObject().put("sessionId", session.id).put("jsonlPath", "").put("jsonl", ""))
    }.toString(2)

    @Test
    fun parsesAetherSessionExport() {
        val result = parseSessionPreviewFile(sessionExport())

        assertTrue(result is SessionPreviewParseResult.Success)
        val parsed = (result as SessionPreviewParseResult.Success).session
        assertEquals("Imported chat", parsed.title)
        assertEquals(listOf(MessageAuthor.User, MessageAuthor.Agent), parsed.messages.map { it.author })
        assertEquals("hi there", parsed.messages.last().text)
        assertEquals("agent-group-1", parsed.messages.last().responseGroupId)
    }

    @Test
    fun ignoresByteOrderMark() {
        assertTrue(parseSessionPreviewFile("﻿" + sessionExport()) is SessionPreviewParseResult.Success)
    }

    @Test
    fun rejectsFullAppExport() {
        val result = parseSessionPreviewFile(sessionExport(exportType = "app"))

        assertEquals(SessionPreviewParseResult.UnsupportedExportType("app"), result)
    }

    @Test
    fun rejectsMalformedJson() {
        assertEquals(SessionPreviewParseResult.Invalid, parseSessionPreviewFile("{not-json"))
    }

    @Test
    fun rejectsSessionWithoutMessagesInsteadOfShowingRecoveryPlaceholder() {
        val raw = JSONObject()
            .put("exportType", SessionExportType)
            .put("session", JSONObject().put("id", "session-1").put("title", "Broken"))
            .toString()

        assertEquals(SessionPreviewParseResult.Invalid, parseSessionPreviewFile(raw))
    }

    @Test
    fun importedSessionGetsFreshIdAndDropsRuntimeState() {
        val imported = session.asImportedSession(nowMillis = 1_800_000_000_000L)

        assertNotEquals(session.id, imported.id)
        assertEquals("session-1800000000000", imported.id)
        assertFalse(imported.agentModeEnabled)
        assertEquals(session.messages, imported.messages)
        assertEquals(session.title, imported.title)
    }
}
