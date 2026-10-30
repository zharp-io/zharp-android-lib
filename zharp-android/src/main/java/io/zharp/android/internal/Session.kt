package io.zharp.android.internal

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** Small persistent key/value storage: SharedPreferences on a device, a map in tests. */
internal interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

internal class MemoryStore : KeyValueStore {
    private val values = ConcurrentHashMap<String, String>()
    override fun get(key: String): String? = values[key]
    override fun put(key: String, value: String) {
        values[key] = value
    }
}

internal object Ids {
    private val random = SecureRandom()

    /** 32 lowercase hex characters: 128 random bits. */
    fun random128(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val out = StringBuilder(32)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

/**
 * The session every span and log carries. A new one starts after [timeoutMillis] in the
 * background, on [rotate] (`clearUser`), and when the app comes back after the process died while
 * away for that long; a process restarted sooner (Android kills background apps freely) keeps it.
 */
internal class SessionManager(
    private val store: KeyValueStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) {
    @Volatile
    private var id: String

    /** When the app last went to the background, or -1 while it's in the foreground. */
    @Volatile
    private var backgroundSince: Long = -1

    init {
        val saved = store.get(KEY_ID)
        val lastSeen = store.get(KEY_LAST_SEEN)?.toLongOrNull()
        id = if (saved != null && lastSeen != null && clock() - lastSeen < timeoutMillis) saved else Ids.random128()
        save()
    }

    /** The current session, starting a new one first if the app has been away too long. */
    fun current(): String {
        val since = backgroundSince
        if (since >= 0 && clock() - since >= timeoutMillis) {
            synchronized(this) {
                if (backgroundSince >= 0 && clock() - backgroundSince >= timeoutMillis) {
                    id = Ids.random128()
                    backgroundSince = clock()
                    save()
                }
            }
        }
        return id
    }

    @Synchronized
    fun onBackground() {
        backgroundSince = clock()
        save()
    }

    @Synchronized
    fun onForeground() {
        current()
        backgroundSince = -1
        save()
    }

    @Synchronized
    fun rotate() {
        id = Ids.random128()
        save()
    }

    /** Remember the time, so a process restarted soon after can keep the session. */
    fun save() {
        guarded(Unit, "Saving the session") {
            store.put(KEY_ID, id)
            store.put(KEY_LAST_SEEN, clock().toString())
        }
    }

    companion object {
        const val TIMEOUT_MILLIS: Long = 30 * 60 * 1000L
        private const val KEY_ID = "session.id"
        private const val KEY_LAST_SEEN = "session.lastSeen"
    }
}

/** What the app has told Zharp about who is using it; global, so it can be set before init. */
internal class UserContext {
    class User(val id: String?, val email: String?, val name: String?)

    @Volatile
    var user: User? = null

    @Volatile
    var screen: String? = null

    val tags: ConcurrentHashMap<String, String> = ConcurrentHashMap()
}
