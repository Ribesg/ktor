/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.server.sessions

import io.ktor.server.application.*
import io.ktor.util.*
import io.ktor.utils.io.*
import kotlin.reflect.*

/**
 * Returns the corresponding session ID for the type [SessionType] or `null` if no session provided.
 * It will crash if no session provider for type [SessionType] installed or no [Sessions] feature installed.
 *
 * @param SessionType to search ID for
 * @return session id or `null` if no session ID sent by the client
 */
public inline fun <reified SessionType : Any> ApplicationCall.sessionId(): String? {
    val name = sessions.findName(SessionType::class)
    return sessionId(name)
}

/**
 * Returns a sessionId for for a single session identified by ID.
 * This will not work if there are multiple sessions by ID were registered or
 * the [Sessions] feature is not installed.
 * If you are using multiple sessions, please use [sessionId] function instead.
 *
 * @return session id or `null` if no session ID sent by the client
 */
public val ApplicationCall.sessionId: String?
    get() {
        val providers = application.feature(Sessions).providers.filter { it.tracker is SessionTrackerById }
        return when (providers.size) {
            0 -> null
            1 -> sessionId(providers[0].name)
            else -> error("Multiple session providers installed. Please use sessionId<S>() function instead.")
        }
    }

@PublishedApi
internal fun ApplicationCall.sessionId(name: String): String? {
    val provider = application.feature(Sessions)
        .providers
        .firstOrNull { it.name == name }
        ?: error("No session provider $name found.")

    val tracker = provider.tracker as? SessionTrackerById ?: error("Provider $name doesn't use session IDs")

    return attributes.getOrNull(tracker.sessionIdKey)
}

/**
 * [SessionTracker] that transfers a Session Id generated by a [sessionIdProvider] in HTTP Headers/Cookies.
 * It uses a [storage] and a [serializer] to store/load serialized/deserialized session content of a specific [type].
 *
 * @property type is a session instance type
 * @property serializer session serializer
 * @property storage session storage to store session
 * @property sessionIdProvider is a function that generates session IDs
 */
public class SessionTrackerById<S : Any>(
    public val type: KClass<S>,
    public val serializer: SessionSerializer<S>,
    public val storage: SessionStorage,
    public val sessionIdProvider: () -> String
) : SessionTracker<S> {
    internal val sessionIdKey: AttributeKey<String> = AttributeKey("SessionId")

    override suspend fun load(call: ApplicationCall, transport: String?): S? {
        val sessionId = transport ?: return null

        call.attributes.put(sessionIdKey, sessionId)
        try {
            return storage.read(sessionId) { channel ->
                val text = channel.readUTF8Line()
                    ?: throw IllegalStateException("Failed to read stored session from $channel")
                serializer.deserialize(text)
            }
        } catch (notFound: NoSuchElementException) {
            call.application.log.debug(
                "Failed to lookup session: ${notFound.message ?: notFound.toString()}. " +
                    "The session id is wrong or outdated."
            )
        }

        // Remove the wrong session identifier if no related session was found
        call.attributes.remove(sessionIdKey)

        return null
    }

    override suspend fun store(call: ApplicationCall, value: S): String {
        val sessionId = call.attributes.computeIfAbsent(sessionIdKey, sessionIdProvider)
        val serialized = serializer.serialize(value)
        storage.write(sessionId) { channel ->
            channel.writeStringUtf8(serialized)
            channel.close()
        }
        return sessionId
    }

    override suspend fun clear(call: ApplicationCall) {
        val sessionId = call.attributes.takeOrNull(sessionIdKey)
        if (sessionId != null) {
            storage.invalidate(sessionId)
        }
    }

    override fun validate(value: S) {
        if (!type.javaObjectType.isAssignableFrom(value.javaClass)) {
            throw IllegalArgumentException("Value for this session tracker expected to be of type $type but was $value")
        }
    }

    override fun toString(): String {
        return "SessionTrackerById: $storage"
    }
}
