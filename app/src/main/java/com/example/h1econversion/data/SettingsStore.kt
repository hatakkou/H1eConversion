package com.example.h1econversion.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.h1econversion.model.BitratePreset
import com.example.h1econversion.model.CodecType
import com.example.h1econversion.model.ContainerType
import com.example.h1econversion.model.ConversionSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** DataStore の拡張プロパティ（プロセス内で単一インスタンス） */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 変換設定の永続化を担当するストア。
 *
 * DataStore (Preferences) を用いて設定値を保存・読み取りします。
 * ViewModel からは [getInstance] でアプリケーションスコープのインスタンスを取得してください。
 */
class SettingsStore private constructor(context: Context) {

    private val dataStore = context.dataStore

    // ---- Flow ----

    /** 現在の設定を継続的に通知する Flow */
    val settingsFlow: Flow<ConversionSettings> = dataStore.data.map { prefs ->
        ConversionSettings(
            codec = parseCodec(prefs[KEY_CODEC]),
            bitrate = parseBitrate(prefs[KEY_BITRATE]),
            container = parseContainer(prefs[KEY_CONTAINER]),
        )
    }

    // ---- 書き込みメソッド ----

    /** コーデックを更新 */
    suspend fun updateCodec(codec: CodecType) {
        dataStore.edit { prefs ->
            prefs[KEY_CODEC] = codec.name
        }
    }

    /** ビットレートを更新 */
    suspend fun updateBitrate(bitrate: BitratePreset) {
        dataStore.edit { prefs ->
            prefs[KEY_BITRATE] = bitrate.bps
        }
    }

    /** コンテナ（拡張子）を更新 */
    suspend fun updateContainer(container: ContainerType) {
        dataStore.edit { prefs ->
            prefs[KEY_CONTAINER] = container.name
        }
    }

    // ---- companion ----

    companion object {
        private val KEY_CODEC = stringPreferencesKey("codec")
        private val KEY_BITRATE = intPreferencesKey("bitrate")
        private val KEY_CONTAINER = stringPreferencesKey("container")

        @Volatile
        private var instance: SettingsStore? = null

        /**
         * アプリケーションスコープのシングルトンインスタンスを返します。
         *
         * @param context アプリケーション Context
         */
        fun getInstance(context: Context): SettingsStore {
            return instance ?: synchronized(this) {
                instance ?: SettingsStore(context.applicationContext).also {
                    instance = it
                }
            }
        }

        // ---- パースヘルパー（欠損・不正値に対するデフォルト値付き） ----

        private fun parseCodec(raw: String?): CodecType {
            return raw?.let { name ->
                try {
                    CodecType.valueOf(name)
                } catch (_: IllegalArgumentException) {
                    CodecType.AAC
                }
            } ?: CodecType.AAC
        }

        private fun parseBitrate(rawBps: Int?): BitratePreset {
            if (rawBps != null) {
                val match = BitratePreset.entries.find { it.bps == rawBps }
                if (match != null) return match
            }
            // デフォルト: 192 kbps（既存アプリと互換）
            return BitratePreset.BITRATE_192
        }

        private fun parseContainer(raw: String?): ContainerType {
            return raw?.let { name ->
                try {
                    ContainerType.valueOf(name)
                } catch (_: IllegalArgumentException) {
                    ContainerType.M4A
                }
            } ?: ContainerType.M4A
        }
    }
}
