package com.example.h1econversion.model

import android.content.Context

/**
 * 複数ファイル選択時のパス一覧を画面間で受け渡すためのホルダー。
 *
 * ナビゲーションルートの URL 長制限を回避するために使用します。
 * 画面遷移直前にセットし、遷移先で読み取ります。
 *
 * プロセス終了時のデータ消失を防ぐため、[init] で渡された Context 経由で
 * SharedPreferences に永続化します。
 */
object MultiFileStateHolder {
    private const val PREFS_NAME = "multi_file_state_holder"
    private const val KEY_PATHS = "selected_paths"
    private const val KEY_PAIRS = "path_gain_pairs"

    private var prefs: android.content.SharedPreferences? = null

    /** 選択されたファイルパスのリスト */
    private var selectedPaths: List<String> = emptyList()

    /** 一括変換用のパスとゲインのペアリスト */
    private var pathGainPairs: List<Pair<String, Float>> = emptyList()

    /**
     * 永続化ストアを初期化します。アプリケーションの起動時に一度だけ呼び出してください。
     */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 保存済みデータがあれば復元
        val savedPaths = prefs?.getString(KEY_PATHS, null)
        if (savedPaths != null && selectedPaths.isEmpty()) {
            selectedPaths = decodeStringList(savedPaths)
        }
        val savedPairs = prefs?.getString(KEY_PAIRS, null)
        if (savedPairs != null && pathGainPairs.isEmpty()) {
            pathGainPairs = decodePairList(savedPairs)
        }
    }

    /**
     * パス一覧を設定します。
     */
    fun set(paths: List<String>) {
        selectedPaths = paths
        prefs?.edit()?.putString(KEY_PATHS, encodeStringList(paths))?.apply()
    }

    /**
     * パス一覧を取得し、クリアします（二重読み込み防止）。
     */
    fun consume(): List<String> {
        val paths = selectedPaths
        selectedPaths = emptyList()
        prefs?.edit()?.remove(KEY_PATHS)?.apply()
        return paths
    }

    /**
     * 一括変換用のパスとゲインのペアリストを設定します。
     */
    fun setPathGainPairs(pairs: List<Pair<String, Float>>) {
        pathGainPairs = pairs
        prefs?.edit()?.putString(KEY_PAIRS, encodePairList(pairs))?.apply()
    }

    /**
     * 一括変換用のパスとゲインのペアリストを取得し、クリアします。
     */
    fun consumePathGainPairs(): List<Pair<String, Float>> {
        val pairs = pathGainPairs
        pathGainPairs = emptyList()
        prefs?.edit()?.remove(KEY_PAIRS)?.apply()
        return pairs
    }

    // ---- シリアライズ補助 ----

    private fun encodeStringList(list: List<String>): String =
        list.joinToString("\n") { it }

    private fun decodeStringList(encoded: String): List<String> =
        if (encoded.isEmpty()) emptyList() else encoded.split("\n")

    private fun encodePairList(list: List<Pair<String, Float>>): String {
        val sb = StringBuilder()
        for ((path, gain) in list) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(path)
            sb.append('|')
            sb.append(gain)
        }
        return sb.toString()
    }

    private fun decodePairList(encoded: String): List<Pair<String, Float>> {
        if (encoded.isEmpty()) return emptyList()
        val result = ArrayList<Pair<String, Float>>(16)
        val lines = encoded.split('\n')
        for (line in lines) {
            val sep = line.lastIndexOf('|')
            val path: String
            val gain: Float
            if (sep >= 0) {
                path = line.substring(0, sep)
                val gainStr = line.substring(sep + 1)
                gain = try { gainStr.toFloat() } catch (_: NumberFormatException) { 1.0f }
            } else {
                path = line
                gain = 1.0f
            }
            result.add(Pair(path, gain))
        }
        return result
    }
}
