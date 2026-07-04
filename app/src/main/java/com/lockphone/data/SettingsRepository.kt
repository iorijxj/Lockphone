package com.lockphone.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lockphone.security.PinHasher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "lockphone_settings")

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

class SettingsRepository(private val context: Context) {
    private object Keys {
        val PIN_SALT = stringPreferencesKey("pin_salt")
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val WHITELIST = stringSetPreferencesKey("whitelist")
        val COOLDOWN_UNTIL = longPreferencesKey("cooldown_until")
        val ORIENTATION_LOCKED = booleanPreferencesKey("orientation_locked")
        val VOLUME_LOCKED = booleanPreferencesKey("volume_locked")
    }

    val whitelist: Flow<Set<String>> =
        context.dataStore.data.map { it[Keys.WHITELIST] ?: emptySet() }

    suspend fun setWhitelist(packages: Set<String>) {
        context.dataStore.edit { it[Keys.WHITELIST] = packages }
    }

    val orientationLocked: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.ORIENTATION_LOCKED] ?: true }

    suspend fun setOrientationLocked(locked: Boolean) {
        context.dataStore.edit { it[Keys.ORIENTATION_LOCKED] = locked }
    }

    val volumeLocked: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.VOLUME_LOCKED] ?: true }.distinctUntilChanged()

    suspend fun setVolumeLocked(locked: Boolean) {
        context.dataStore.edit { it[Keys.VOLUME_LOCKED] = locked }
    }

    suspend fun isPinSet(): Boolean =
        context.dataStore.data.first().contains(Keys.PIN_HASH)

    suspend fun setPin(pin: String) {
        val salt = PinHasher.newSalt()
        context.dataStore.edit {
            it[Keys.PIN_SALT] = salt.toHex()
            it[Keys.PIN_HASH] = PinHasher.hash(pin, salt)
        }
    }

    suspend fun verifyPin(pin: String): Boolean {
        val prefs = context.dataStore.data.first()
        val salt = prefs[Keys.PIN_SALT]?.hexToBytes() ?: return false
        val hash = prefs[Keys.PIN_HASH] ?: return false
        return PinHasher.verify(pin, salt, hash)
    }

    suspend fun getCooldownUntil(): Long =
        context.dataStore.data.first()[Keys.COOLDOWN_UNTIL] ?: 0L

    suspend fun setCooldownUntil(timestamp: Long) {
        context.dataStore.edit { it[Keys.COOLDOWN_UNTIL] = timestamp }
    }
}
