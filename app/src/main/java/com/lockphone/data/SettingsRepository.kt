package com.lockphone.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lockphone.security.PinHasher
import com.lockphone.time.TimeWindow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "lockphone_settings")

private const val MAX_PIN_FAILURES = 100

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

private fun encodeIntMap(m: Map<String, Int>): String =
    m.entries.joinToString("\n") { "${it.key}=${it.value}" }

private fun decodeIntMap(s: String?): Map<String, Int> =
    s?.split("\n")
        ?.filter { it.isNotBlank() }
        ?.mapNotNull { line ->
            val i = line.lastIndexOf('=')
            if (i <= 0) return@mapNotNull null
            val v = line.substring(i + 1).toIntOrNull() ?: return@mapNotNull null
            line.substring(0, i) to v
        }
        ?.toMap()
        ?: emptyMap()

private fun encodeWindows(list: List<TimeWindow>): String =
    list.joinToString(",") { "${it.startMinute}-${it.endMinute}" }

private fun decodeWindows(s: String?): List<TimeWindow> =
    s?.split(",")
        ?.filter { it.isNotBlank() }
        ?.mapNotNull { entry ->
            val p = entry.split("-")
            if (p.size != 2) return@mapNotNull null
            val start = p[0].toIntOrNull() ?: return@mapNotNull null
            val end = p[1].toIntOrNull() ?: return@mapNotNull null
            TimeWindow(start.coerceIn(0, 1439), end.coerceIn(0, 1439))
        }
        ?: emptyList()

/** 计时状态快照，供前台服务一次读齐 */
data class UsageSnapshot(
    val date: String,
    val used: Map<String, Int>,
    val bonus: Map<String, Int>,
    val suspended: Set<String>,
    val quota: Map<String, Int>,
)

/** 可用时段配置快照，供前台服务一次读齐 */
data class CurfewSnapshot(
    val enabled: Boolean,
    val windows: List<TimeWindow>,
    val tempUnlockUntil: Long,
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val PIN_SALT = stringPreferencesKey("pin_salt")
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val WHITELIST = stringSetPreferencesKey("whitelist")
        val COOLDOWN_UNTIL = longPreferencesKey("cooldown_until")
        val ORIENTATION_LOCKED = booleanPreferencesKey("orientation_locked")
        val VOLUME_LOCKED = booleanPreferencesKey("volume_locked")
        val PIN_FAILURES = stringPreferencesKey("pin_failures")
        val APP_QUOTA = stringPreferencesKey("app_quota")
        val USAGE_DATE = stringPreferencesKey("usage_date")
        val USAGE_USED = stringPreferencesKey("usage_used")
        val BONUS_TODAY = stringPreferencesKey("bonus_today")
        val QUOTA_SUSPENDED = stringSetPreferencesKey("quota_suspended")
        val CURFEW_ENABLED = booleanPreferencesKey("curfew_enabled")
        val CURFEW_WINDOWS = stringPreferencesKey("curfew_windows")
        val CURFEW_TEMP_UNLOCK_UNTIL = longPreferencesKey("curfew_temp_unlock_until")
        val CURFEW_ACTIVE = booleanPreferencesKey("curfew_active")
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

    val pinFailures: Flow<List<Long>> =
        context.dataStore.data.map { prefs ->
            prefs[Keys.PIN_FAILURES]
                ?.split("\n")
                ?.filter { it.isNotBlank() }
                ?.mapNotNull { it.toLongOrNull() }
                ?: emptyList()
        }

    suspend fun recordPinFailure(timestamp: Long) {
        context.dataStore.edit { prefs ->
            val existing = prefs[Keys.PIN_FAILURES]
                ?.split("\n")
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            val updated = (existing + timestamp.toString()).takeLast(MAX_PIN_FAILURES)
            prefs[Keys.PIN_FAILURES] = updated.joinToString("\n")
        }
    }

    // 每应用日配额（分钟）：有条目=限时，无条目=不限时
    val appQuota: Flow<Map<String, Int>> =
        context.dataStore.data.map { decodeIntMap(it[Keys.APP_QUOTA]) }

    suspend fun setAppQuota(pkg: String, minutes: Int?) {
        context.dataStore.edit { prefs ->
            val map = decodeIntMap(prefs[Keys.APP_QUOTA]).toMutableMap()
            if (minutes == null) map.remove(pkg) else map[pkg] = minutes
            prefs[Keys.APP_QUOTA] = encodeIntMap(map)
        }
    }

    // 今日各应用已用秒数
    val usageUsed: Flow<Map<String, Int>> =
        context.dataStore.data.map { decodeIntMap(it[Keys.USAGE_USED]) }

    // 因配额被挂起的应用（供桌面置灰、跨天/加时解挂）
    val quotaSuspended: Flow<Set<String>> =
        context.dataStore.data.map { it[Keys.QUOTA_SUSPENDED] ?: emptySet() }

    suspend fun usageSnapshot(): UsageSnapshot {
        val prefs = context.dataStore.data.first()
        return UsageSnapshot(
            date = prefs[Keys.USAGE_DATE] ?: "",
            used = decodeIntMap(prefs[Keys.USAGE_USED]),
            bonus = decodeIntMap(prefs[Keys.BONUS_TODAY]),
            suspended = prefs[Keys.QUOTA_SUSPENDED] ?: emptySet(),
            quota = decodeIntMap(prefs[Keys.APP_QUOTA]),
        )
    }

    /** 前台服务 tick 后一次性落盘计时状态 */
    suspend fun applyUsage(
        date: String,
        used: Map<String, Int>,
        bonus: Map<String, Int>,
        suspended: Set<String>,
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.USAGE_DATE] = date
            prefs[Keys.USAGE_USED] = encodeIntMap(used)
            prefs[Keys.BONUS_TODAY] = encodeIntMap(bonus)
            prefs[Keys.QUOTA_SUSPENDED] = suspended
        }
    }

    /** 清除某应用的配额挂起标记（改配额/取消白名单时用，避免永久挂死） */
    suspend fun clearQuotaSuspended(pkg: String) {
        context.dataStore.edit {
            it[Keys.QUOTA_SUSPENDED] = (it[Keys.QUOTA_SUSPENDED] ?: emptySet()) - pkg
        }
    }

    /** 家长加时：追加今日 bonus 秒数，并解除因配额产生的挂起标记 */
    suspend fun addBonus(pkg: String, seconds: Int) {
        context.dataStore.edit { prefs ->
            val bonus = decodeIntMap(prefs[Keys.BONUS_TODAY]).toMutableMap()
            bonus[pkg] = (bonus[pkg] ?: 0) + seconds
            prefs[Keys.BONUS_TODAY] = encodeIntMap(bonus)
            prefs[Keys.QUOTA_SUSPENDED] = (prefs[Keys.QUOTA_SUSPENDED] ?: emptySet()) - pkg
        }
    }

    val curfewEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.CURFEW_ENABLED] ?: false }

    suspend fun setCurfewEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.CURFEW_ENABLED] = enabled }
    }

    val curfewWindows: Flow<List<TimeWindow>> =
        context.dataStore.data.map { decodeWindows(it[Keys.CURFEW_WINDOWS]) }

    // 时段增/改/删都在单个 edit 事务内读改写，避免 UI 用过期列表快照互相覆盖
    suspend fun addCurfewWindow(window: TimeWindow) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CURFEW_WINDOWS] = encodeWindows(decodeWindows(prefs[Keys.CURFEW_WINDOWS]) + window)
        }
    }

    suspend fun updateCurfewWindow(index: Int, window: TimeWindow) {
        context.dataStore.edit { prefs ->
            val list = decodeWindows(prefs[Keys.CURFEW_WINDOWS])
            if (index !in list.indices) return@edit
            prefs[Keys.CURFEW_WINDOWS] =
                encodeWindows(list.mapIndexed { i, w -> if (i == index) window else w })
        }
    }

    suspend fun removeCurfewWindow(index: Int) {
        context.dataStore.edit { prefs ->
            val list = decodeWindows(prefs[Keys.CURFEW_WINDOWS])
            prefs[Keys.CURFEW_WINDOWS] = encodeWindows(list.filterIndexed { i, _ -> i != index })
        }
    }

    // 当前是否处于非授权时段（由 TimeGuardService 每 tick 计算写回，UI 只读观察）
    val curfewActive: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.CURFEW_ACTIVE] ?: false }.distinctUntilChanged()

    suspend fun setCurfewActive(active: Boolean) {
        context.dataStore.edit { it[Keys.CURFEW_ACTIVE] = active }
    }

    /** 家长临时解锁截止时间戳（绝对时间，跨重启生效） */
    suspend fun setTempUnlockUntil(untilMillis: Long) {
        context.dataStore.edit { it[Keys.CURFEW_TEMP_UNLOCK_UNTIL] = untilMillis }
    }

    suspend fun curfewSnapshot(): CurfewSnapshot {
        val prefs = context.dataStore.data.first()
        return CurfewSnapshot(
            enabled = prefs[Keys.CURFEW_ENABLED] ?: false,
            windows = decodeWindows(prefs[Keys.CURFEW_WINDOWS]),
            tempUnlockUntil = prefs[Keys.CURFEW_TEMP_UNLOCK_UNTIL] ?: 0L,
        )
    }
}
