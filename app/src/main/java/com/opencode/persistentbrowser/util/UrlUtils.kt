package com.opencode.persistentbrowser.util

import java.util.Locale

/**
 * URL parsing, validation and normalization.
 *
 * Accepts:
 *  - http:// and https:// URLs (with optional userinfo, port, path, query, fragment)
 *  - scheme-less "host[:port][/path]" forms, which are promoted to https://
 *
 * Rejects anything that is not a valid http(s) URL with a host.
 */
object UrlUtils {

    private val IPV4_REGEX = Regex(
        """^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$"""
    )

    data class ParsedUrl(
        val raw: String,
        val scheme: String,
        val host: String,
        val port: Int,
        val path: String,
        val query: String?,
        val fragment: String?,
        val userInfo: String?
    ) {
        val origin: String get() = "$scheme://$host" + if (port > 0) ":$port" else ""
        val isSecure: Boolean get() = scheme == "https"
    }

    /** Returns true if [text] looks like a URL we can load (http/https or scheme-less host). */
    fun isUrlLike(text: String): Boolean = parse(text) != null

    /**
     * Parse and normalize [text]. Returns null if it is not a usable URL.
     * Scheme-less inputs are promoted to https:// when they contain a dot or are an IP,
     * otherwise treated as invalid (avoids pasting arbitrary sentences).
     */
    fun parse(text: String): ParsedUrl? {
        var t = text.trim()
        if (t.isEmpty() || t.length > 2048) return null
        if (t.contains(' ')) return null

        var scheme = ""
        val schemeIdx = t.indexOf("://")
        if (schemeIdx > 0) {
            val s = t.substring(0, schemeIdx).lowercase(Locale.US)
            if (s != "http" && s != "https") return null
            scheme = s
            t = t.substring(schemeIdx + 3)
        }

        // Split off fragment and query.
        var fragment: String? = null
        var query: String? = null
        val fragIdx = t.indexOf('#')
        if (fragIdx >= 0) {
            fragment = t.substring(fragIdx + 1)
            t = t.substring(0, fragIdx)
        }
        val queryIdx = t.indexOf('?')
        if (queryIdx >= 0) {
            query = t.substring(queryIdx + 1)
            t = t.substring(0, queryIdx)
        }

        // Split userinfo.
        var userInfo: String? = null
        val atIdx = t.lastIndexOf('@')
        if (atIdx >= 0) {
            userInfo = t.substring(0, atIdx)
            t = t.substring(atIdx + 1)
        }

        // Split path.
        var path = ""
        val pathIdx = t.indexOf('/')
        if (pathIdx >= 0) {
            path = t.substring(pathIdx)
            t = t.substring(0, pathIdx)
        }

        // Split host:port. port = -1 means "not specified".
        var host = t
        var port = -1
        if (t.startsWith("[")) {
            // IPv6 literal
            val end = t.indexOf(']')
            if (end < 0) return null
            host = t.substring(0, end + 1)
            val rest = t.substring(end + 1)
            if (rest.startsWith(":")) {
                port = rest.substring(1).toIntOrNull() ?: return null
            } else if (rest.isNotEmpty()) {
                return null
            }
        } else {
            val colonIdx = t.indexOf(':')
            if (colonIdx >= 0) {
                host = t.substring(0, colonIdx)
                port = t.substring(colonIdx + 1).toIntOrNull() ?: return null
            }
        }

        if (host.isEmpty()) return null
        if (port != -1 && port !in 1..65535) return null

        if (scheme.isEmpty()) {
            // Scheme-less: only accept if it looks like a host (has a dot or is an IP).
            val looksLikeHost = host.contains('.') || IPV4_REGEX.matches(host) || host.startsWith("[")
            if (!looksLikeHost) return null
            scheme = "https"
        }

        // Validate host characters.
        val hostNoBrackets = host.removePrefix("[").removeSuffix("]")
        if (hostNoBrackets.isEmpty()) return null
        if (!hostNoBrackets.all { it.isLetterOrDigit() || it in ".-_~!$&'()*+,;=:[%" }) return null

        return ParsedUrl(
            raw = text.trim(),
            scheme = scheme,
            host = host,
            port = port,
            path = path,
            query = query,
            fragment = fragment,
            userInfo = userInfo
        )
    }

    /** Build the final URL string to load, adding a scheme if needed. */
    fun normalize(text: String): String? {
        val p = parse(text) ?: return null
        val sb = StringBuilder()
        sb.append(p.scheme).append("://")
        if (p.userInfo != null) sb.append(p.userInfo).append('@')
        sb.append(p.host)
        if (p.port > 0) sb.append(':').append(p.port)
        sb.append(p.path)
        if (p.query != null) sb.append('?').append(p.query)
        if (p.fragment != null) sb.append('#').append(p.fragment)
        return sb.toString()
    }

    /** Host for display in the notification (never includes credentials). */
    fun displayHost(text: String): String {
        val p = parse(text) ?: return text.trim()
        return p.host + if (p.port > 0) ":${p.port}" else ""
    }
}
