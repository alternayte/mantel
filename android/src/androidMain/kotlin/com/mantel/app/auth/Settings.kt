package com.mantel.app.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mantel")

/**
 * What survives a restart: which server this install talks to, and the session it holds there.
 *
 * The server address is a value the person sets because Mantel is self-hosted (SDD.md 11) and there
 * is no address the app could assume. The verifier is here only while a sign-in is in flight: the
 * browser is a separate process and the app may be killed behind it.
 */
class Settings(private val context: Context) {
    private val serverUrlKey = stringPreferencesKey("server_url")
    private val sessionKey = stringPreferencesKey("session")
    private val verifierKey = stringPreferencesKey("pending_verifier")

    val serverUrl: Flow<String?> = context.dataStore.data.map { it[serverUrlKey] }
    val session: Flow<String?> = context.dataStore.data.map { it[sessionKey] }

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { it[serverUrlKey] = url.trimEnd('/') }
    }

    suspend fun setSession(secret: String) {
        context.dataStore.edit { it[sessionKey] = secret }
    }

    suspend fun clearSession() {
        context.dataStore.edit { it.remove(sessionKey) }
    }

    suspend fun startFlow(verifier: String) {
        context.dataStore.edit { it[verifierKey] = verifier }
    }

    /** Reads the verifier and forgets it in one step: a flow is finished once, or not at all. */
    suspend fun takeVerifier(): String? {
        var verifier: String? = null
        context.dataStore.edit {
            verifier = it[verifierKey]
            it.remove(verifierKey)
        }
        return verifier
    }
}
