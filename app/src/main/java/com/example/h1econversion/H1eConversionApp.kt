package com.example.h1econversion

import android.app.Application
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * アプリケーション全体の初期化を担当。
 * 未捕捉例外ハンドラをプロセス全体で一度だけ設定し、
 * クラッシュログのローテーション管理も行う。
 */
class H1eConversionApp : Application() {

    companion object {
        private const val TAG = "H1eConversionApp"
        /** 保持するクラッシュログの最大ファイル数 */
        private const val MAX_CRASH_LOGS = 5
        /** startupCacheCleanup() の排他制御用ロックオブジェクト */
        private val cacheCleanupLock = Any()
        /** キャッシュクリーンアップが既に実行済みかどうか */
        @Volatile
        private var cacheCleanupDone = false
    }

    @Volatile
    private var handlerInstalled = false

    override fun onCreate() {
        super.onCreate()

        // MultiFileStateHolder の永続化ストアを初期化
        com.example.h1econversion.model.MultiFileStateHolder.init(this)

        // キャッシュクリーンアップ（プロセス起動時に1回だけ実行）
        startupCacheCleanup()

        // プロセス全体で一度だけハンドラを設定
        if (!handlerInstalled) {
            synchronized(this) {
                if (!handlerInstalled) {
                    installUncaughtExceptionHandler()
                    handlerInstalled = true
                }
            }
        }
    }

    /**
     * グローバルな未捕捉例外ハンドラを設定。
     * クラッシュ原因をログに出力し、ファイルにも書き出す。
     * デフォルトハンドラにも転送する。
     */
    private fun installUncaughtExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "UNCAUGHT EXCEPTION in thread ${thread.name}: ${throwable.message}", throwable)
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                val crashLog = File(applicationContext.filesDir, "crash_${System.currentTimeMillis()}.log")
                crashLog.writeText("Thread: ${thread.name}\n${sw.toString()}")
                Log.e(TAG, "Crash log written to: ${crashLog.absolutePath}")
                // 古いクラッシュログをローテーション（最大保持数を超えた分を削除）
                rotateCrashLogs()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write crash log", e)
            }
            // デフォルトハンドラにも転送
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * crash_*.log ファイルを古い順に削除し、最大保持数に収める。
     */
    private fun rotateCrashLogs() {
        try {
            val crashFiles = applicationContext.filesDir.listFiles { f ->
                f.name.startsWith("crash_") && f.name.endsWith(".log")
            } ?: return
            if (crashFiles.size > MAX_CRASH_LOGS) {
                crashFiles.sortedBy { it.lastModified() }
                    .take(crashFiles.size - MAX_CRASH_LOGS)
                    .forEach { it.delete() }
            }
        } catch (_: Exception) { /* ローテーション失敗は無視 */ }
    }

    /**
     * アプリ起動時にキャッシュのクリーンアップを実行します。
     *
     * 以下の2つの処理をバックグラウンドスレッドで行います:
     * 1. cacheDir/converted/ 内の変換済みM4Aファイルをすべて削除
     * 2. filesDir/recordings/ 内の7日以上経過したWAVファイルを削除
     *
     * この処理はプロセス起動時に1回だけ実行されます（排他制御あり）。
     */
    private fun startupCacheCleanup() {
        synchronized(cacheCleanupLock) {
            if (cacheCleanupDone) {
                Log.d(TAG, "startupCacheCleanup: already done, skipping")
                return
            }
            cacheCleanupDone = true
        }

        // バックグラウンドスレッドで実行（UIスレッドをブロックしない）
        Thread({
            try {
                // 1. cacheDir/converted/ を全削除
                val convertedDir = File(cacheDir, "converted")
                if (convertedDir.exists() && convertedDir.isDirectory) {
                    val cacheFiles = convertedDir.listFiles() ?: emptyArray()
                    var cacheDeleted = 0
                    for (file in cacheFiles) {
                        if (file.delete()) cacheDeleted++
                    }
                    if (cacheDeleted > 0) {
                        Log.i(TAG, "startupCacheCleanup: deleted $cacheDeleted converted cache file(s)")
                    }
                }

                // 2. 7日以上経過した録音ファイルを削除
                val localRepo = com.example.h1econversion.audio.LocalFileRepository(this)
                val deletedRecordings = localRepo.cleanupOldRecordings()
                if (deletedRecordings > 0) {
                    Log.i(TAG, "startupCacheCleanup: deleted $deletedRecordings old recording(s)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "startupCacheCleanup: failed", e)
            }
        }, "CacheCleanup").start()
    }
}
