/*
 * Copyright 2014-2022 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.darwin.internal

import io.ktor.http.*
import io.ktor.util.*
import platform.Foundation.*

internal fun Url.toNSUrl(): NSURL {
    val components = NSURLComponents()

    components.scheme = protocol.name

    components.percentEncodedUser = encodedUser?.sanitize(NSCharacterSet.URLUserAllowedCharacterSet, user)
    components.percentEncodedPassword = encodedPassword?.sanitize(NSCharacterSet.URLUserAllowedCharacterSet, password)

    components.percentEncodedHost = host.sanitize(NSCharacterSet.URLHostAllowedCharacterSet, host)
    if (port != DEFAULT_PORT && port != protocol.defaultPort) {
        components.port = NSNumber(port)
    }

    components.percentEncodedPath =
        encodedPath.sanitize(NSCharacterSet.URLPathAllowedCharacterSet, pathSegments.joinToString("/"))

    if (encodedQuery.all { NSCharacterSet.URLQueryAllowedCharacterSet.characterIsMember(it.code.toUShort()) }) {
        components.percentEncodedQuery = encodedQuery.takeIf { it.isNotEmpty() }
    } else {
        components.percentEncodedQueryItems = parameters.toMap()
            .flatMap { (key, value) ->
                if (value.isEmpty()) listOf(key to null) else value.map { key to it }
            }
            .map { NSURLQueryItem(it.first.encodeQueryPart(), it.second?.encodeQueryPart()) }
    }

    if (encodedFragment.isNotEmpty()) {
        components.percentEncodedFragment =
            encodedFragment.sanitize(NSCharacterSet.URLFragmentAllowedCharacterSet, fragment)
    }

    return components.URL!!
}

private fun String.sanitize(allowed: NSCharacterSet, decoded: String?): String? = when {
    isEncoded(allowed) -> this
    else -> decoded?.asNSString()?.stringByAddingPercentEncodingWithAllowedCharacters(allowed)
}

private fun String.encodeQueryPart(): String =
    asNSString().stringByAddingPercentEncodingWithAllowedCharacters(NSCharacterSet.URLQueryAllowedCharacterSet)!!
        .replace("&", "%26")
        .replace(";", "%3B")
        .replace("=", "%3D")

private fun String.isEncoded(allowed: NSCharacterSet) = all { allowed.characterIsMember(it.code.toUShort()) }

@Suppress("CAST_NEVER_SUCCEEDS")
private fun String.asNSString(): NSString = this as NSString
