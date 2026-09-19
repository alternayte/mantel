package com.mantel.app.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mantel.app.api.Me
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mantel")

/**
 * What survives a restart: which server this install talks to, and the session it holds there.
 *
 * The server address is a value the person sets because Mantel is self-hosted (SDD.md 11) and there
 * is no address the app could assume. The verifier is here only while a sign-in is in flight: the
 * browser is a separate process and the app may be killed behind it.
 *
 * It is an interface because `AppModel` is tested off a device and DataStore needs one. There is one
 * real implementation and there will not be a second.
 */
interface Settings {
    val serverUrl: Flow<String?>
    val session: Flow<String?>

    /**
     * The last account the server described. It is kept so the app opens signed in while offline:
     * the session is still valid, and being unable to reach the server is not the same as being
     * signed out.
     */
    val me: Flow<Me?>

    suspend fun setMe(me: Me)

    suspend fun setServerUrl(url: String)

    suspend fun setSession(secret: String)

    suspend fun clearSession()

    suspend fun startFlow(verifier: String)

    /** Reads the verifier and forgets it in one step: a flow is finished once, or not at all. */
    suspend fun takeVerifier(): String?
}

class StoredSettings(private val context: Context) : Settings {
    private val serverUrlKey = stringPreferencesKey("server_url")
    private val sessionKey = stringPreferencesKey("session")
    private val verifierKey = stringPreferencesKey("pending_verifier")
    private val meKey = stringPreferencesKey("me")

    override val serverUrl: Flow<String?> = context.dataStore.data.map { it[serverUrlKey] }
    override val session: Flow<String?> = context.dataStore.data.map { it[sessionKey] }

    override val me: Flow<Me?> =
        context.dataStore.data.map { preferences ->
            preferences[meKey]?.let { runCatching { Json.decodeFromString<Me>(it) }.getOrNull() }
        }

    override suspend fun setMe(me: Me) {
        context.dataStore.edit { it[meKey] = Json.encodeToString(me) }
    }

    override suspend fun setServerUrl(url: String) {
        context.dataStore.edit { it[serverUrlKey] = url.trimEnd('/') }
    }

    override suspend fun setSession(secret: String) {
        context.dataStore.edit { it[sessionKey] = secret }
    }

    override suspend fun clearSession() {
        context.dataStore.edit {
            it.remove(sessionKey)
            it.remove(meKey)
        }
    }

    override suspend fun startFlow(verifier: String) {
        context.dataStore.edit { it[verifierKey] = verifier }
    }

    override suspend fun takeVerifier(): String? {
        var verifier: String? = null
        context.dataStore.edit {
            verifier = it[verifierKey]
            it.remove(verifierKey)
        }
        return verifier
    }
}
