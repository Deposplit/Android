package com.deposplit.value_objects

import java.net.URI

/**
 * What names a relay: an `http` or `https` URL with a host, and nothing after its path.
 *
 * Every request is this string with an endpoint's path appended, so a query or a fragment would
 * swallow that path, and a trailing slash would double the one it starts with. Anything else —
 * `172.22.114.13:9000` without its scheme, a `file:` URL — is refused where it is entered rather
 * than stored, because once stored it fails on every sync for as long as the contact exists.
 *
 * iOS's and phon's `RelayBaseUrl` apply the same rule.
 */
object RelayBaseUrl {

    /** The relay [input] names, trimmed and without trailing slashes, or `null` when it names none. */
    fun parse(input: String): String? {
        val trimmed = input.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrEmpty()) return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        return trimmed.trimEnd('/')
    }
}
