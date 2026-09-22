package com.fersaiyan.cyanbridge.chat

import com.fersaiyan.cyanbridge.shared.chat.ChatRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStoreTest {

    @After
    fun tearDown() {
        ChatStore.clearAll()
    }

    @Test
    fun createThread_isListed() {
        val t = ChatStore.createThread(title = "Test")
        val list = ChatStore.listThreads()
        assertEquals(1, list.size)
        assertEquals(t.id, list[0].id)
        assertEquals("Test", list[0].title)
    }

    @Test
    fun addMessage_updatesTitleFromFirstUserMessage() {
        val t = ChatStore.createThread()
        ChatStore.addMessage(t.id, ChatRole.USER, "Hello there")

        val thread = ChatStore.getThread(t.id)
        assertNotNull(thread)
        assertTrue(thread!!.title.startsWith("Hello"))
    }

    @Test
    fun listMessages_returnsInOrder() {
        val t = ChatStore.createThread(title = "X")
        ChatStore.addMessage(t.id, ChatRole.USER, "a")
        ChatStore.addMessage(t.id, ChatRole.ASSISTANT, "b")

        val msgs = ChatStore.listMessages(t.id)
        assertEquals(2, msgs.size)
        assertEquals("a", msgs[0].content)
        assertEquals("b", msgs[1].content)
    }

    @Test
    fun streamedAssistantUpdatesOneMessageAndPublishesChanges() {
        val thread = ChatStore.createThread("BV300")
        val draft = ChatStore.addMessage(thread.id, ChatRole.ASSISTANT, "Первое")
        val firstRevision = ChatStore.messageChanges.value!!.revision
        assertTrue(ChatStore.updateAssistantMessage(thread.id, draft.id, "Первое предложение. Второе"))
        val messages = ChatStore.listMessages(thread.id)
        assertEquals(1, messages.size)
        assertEquals(draft.id, messages.single().id)
        assertEquals("Первое предложение. Второе", messages.single().content)
        assertTrue(ChatStore.messageChanges.value!!.revision > firstRevision)
    }

    @Test
    fun photoAttachmentBelongsToUserMessageAndIsNotEmbeddedInText() {
        val thread = ChatStore.createThread("BV300 session")
        val question = ChatStore.addMessage(thread.id, ChatRole.USER, "Что передо мной?", imageAttachmentName = "bv300_123.jpg")
        ChatStore.addMessage(thread.id, ChatRole.ASSISTANT, "Перед вами компьютер")

        assertEquals("bv300_123.jpg", ChatStore.listMessages(thread.id).first().imageAttachmentName)
        assertEquals("Что передо мной?", question.content)
        assertEquals(null, ChatStore.listMessages(thread.id).last().imageAttachmentName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun attachmentNameCannotEscapePrivateDirectory() {
        val thread = ChatStore.createThread("BV300 session")
        ChatStore.addMessage(thread.id, ChatRole.USER, "Фото", imageAttachmentName = "../other.jpg")
    }
}
