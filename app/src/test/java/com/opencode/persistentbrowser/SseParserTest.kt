package com.opencode.persistentbrowser

import com.opencode.persistentbrowser.net.SseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {

    @Test
    fun `parses a simple event`() {
        val parser = SseParser()
        val events = parser.feed("data: hello\n\n")
        assertEquals(1, events.size)
        assertEquals("hello", events[0].data)
        assertNull(events[0].id)
    }

    @Test
    fun `parses event with id and type`() {
        val parser = SseParser()
        val events = parser.feed("id: 42\nevent: message\ndata: {\"a\":1}\n\n")
        assertEquals(1, events.size)
        assertEquals("42", events[0].id)
        assertEquals("message", events[0].event)
        assertEquals("{\"a\":1}", events[0].data)
    }

    @Test
    fun `parses multi-line data`() {
        val parser = SseParser()
        val events = parser.feed("data: line1\ndata: line2\n\n")
        assertEquals(1, events.size)
        assertEquals("line1\nline2", events[0].data)
    }

    @Test
    fun `handles chunked input`() {
        val parser = SseParser()
        assertTrue(parser.feed("data: par").isEmpty())
        assertTrue(parser.feed("tial").isEmpty())
        val events = parser.feed(" data\n\n")
        assertEquals(1, events.size)
        assertEquals("partial data", events[0].data)
    }

    @Test
    fun `ignores comments and heartbeats`() {
        val parser = SseParser()
        val events = parser.feed(": heartbeat\n\ndata: real\n\n")
        assertEquals(1, events.size)
        assertEquals("real", events[0].data)
    }

    @Test
    fun `parses multiple events in one chunk`() {
        val parser = SseParser()
        val events = parser.feed("data: one\n\ndata: two\n\n")
        assertEquals(2, events.size)
        assertEquals("one", events[0].data)
        assertEquals("two", events[1].data)
    }

    @Test
    fun `handles carriage returns`() {
        val parser = SseParser()
        val events = parser.feed("data: cr\r\n\r\n")
        assertEquals(1, events.size)
        assertEquals("cr", events[0].data)
    }
}
