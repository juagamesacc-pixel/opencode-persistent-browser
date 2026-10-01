package com.opencode.persistentbrowser

import com.opencode.persistentbrowser.util.UrlUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlUtilsTest {

    @Test
    fun `parses https url with path query and fragment`() {
        val p = UrlUtils.parse("https://example.com/path/to?x=1&y=2#frag")!!
        assertEquals("https", p.scheme)
        assertEquals("example.com", p.host)
        assertEquals("/path/to", p.path)
        assertEquals("x=1&y=2", p.query)
        assertEquals("frag", p.fragment)
        assertEquals("https://example.com", p.origin)
    }

    @Test
    fun `parses http url with port`() {
        val p = UrlUtils.parse("http://192.168.1.10:3000/")!!
        assertEquals("http", p.scheme)
        assertEquals("192.168.1.10", p.host)
        assertEquals(3000, p.port)
        assertEquals("http://192.168.1.10:3000", p.origin)
    }

    @Test
    fun `parses url with userinfo`() {
        val p = UrlUtils.parse("https://user:pass@example.com/")!!
        assertEquals("user:pass", p.userInfo)
        assertEquals("example.com", p.host)
    }

    @Test
    fun `promotes scheme-less host to https`() {
        val p = UrlUtils.parse("example.com:8080/path")!!
        assertEquals("https", p.scheme)
        assertEquals("example.com", p.host)
        assertEquals(8080, p.port)
    }

    @Test
    fun `rejects non-url text`() {
        assertNull(UrlUtils.parse("hello world"))
        assertNull(UrlUtils.parse("just some text"))
        assertNull(UrlUtils.parse("ftp://example.com"))
        assertNull(UrlUtils.parse(""))
    }

    @Test
    fun `rejects scheme-less without dot`() {
        assertNull(UrlUtils.parse("localhostpath"))
    }

    @Test
    fun `normalize adds scheme when missing`() {
        assertEquals("https://example.com", UrlUtils.normalize("example.com"))
        assertEquals("http://localhost:3000", UrlUtils.normalize("http://localhost:3000"))
    }

    @Test
    fun `isUrlLike matches valid urls`() {
        assertTrue(UrlUtils.isUrlLike("https://example.com"))
        assertTrue(UrlUtils.isUrlLike("http://localhost:3000"))
        assertTrue(UrlUtils.isUrlLike("example.com/path"))
    }

    @Test
    fun `displayHost strips credentials`() {
        assertEquals("example.com", UrlUtils.displayHost("https://user:pass@example.com/"))
        assertEquals("example.com:8443", UrlUtils.displayHost("https://example.com:8443/x"))
    }
}
