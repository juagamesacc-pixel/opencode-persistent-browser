package com.opencode.persistentbrowser.net

/**
 * Incremental Server-Sent Events parser.
 *
 * Parses the SSE wire format:
 *   id: <id>\n
 *   event: <type>\n
 *   data: <json>\n
 *   \n            <- dispatch event
 *
 * Handles multi-line data (joined with \n), comments (: ...), and
 * optional leading space after the field colon, per the SSE spec.
 * Thread-confined: call from a single reader thread.
 */
class SseParser {

    data class Event(
        val id: String?,
        val event: String?,
        val data: String
    )

    private val buffer = StringBuilder()

    private var curId: String? = null
    private var curEvent: String? = null
    private val curData = StringBuilder()
    private var curHasData = false

    /** Feed a chunk of text. Returns events that became complete. */
    fun feed(chunk: String): List<Event> {
        buffer.append(chunk)
        val out = ArrayList<Event>()
        var start = 0
        var i = 0
        val n = buffer.length
        while (i < n) {
            val c = buffer[i]
            if (c == '\n') {
                val line = buffer.substring(start, i).removeSuffix("\r")
                start = i + 1
                val ev = handleLine(line)
                if (ev != null) out.add(ev)
            }
            i++
        }
        // Keep the incomplete trailing line for the next chunk.
        if (start > 0) buffer.delete(0, start)
        return out
    }

    private fun handleLine(line: String): Event? {
        if (line.isEmpty()) {
            if (!curHasData) return null
            val ev = Event(
                id = curId,
                event = curEvent,
                data = curData.toString()
            )
            curId = null
            curEvent = null
            curData.clear()
            curHasData = false
            return ev
        }
        if (line.startsWith(":")) return null // comment / heartbeat

        val colon = line.indexOf(':')
        val field: String
        var value: String
        if (colon < 0) {
            field = line
            value = ""
        } else {
            field = line.substring(0, colon)
            value = line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
        }
        when (field) {
            "id" -> curId = value
            "event" -> curEvent = value
            "data" -> {
                if (curHasData) curData.append('\n')
                curData.append(value)
                curHasData = true
            }
        }
        return null
    }
}
