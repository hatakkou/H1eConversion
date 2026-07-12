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
    }

    @Volatile
    private var handlerInstalled = false

    override fun onCreate() {
        super.onCreate()

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
}
