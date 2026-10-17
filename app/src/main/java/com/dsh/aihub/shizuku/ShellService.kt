package com.dsh.aihub.shizuku

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * [IShellService] 的实现，跑在 Shizuku 拉起的独立进程里。
 *
 * 因此这里 `Runtime.exec` 拿到的是 shell（或 root）权限 —— 具体取决于
 * 用户是以 ADB 还是 root 方式启动的 Shizuku。
 */
class ShellService : Service() {

    fun exec(command: String): Array<String> = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
        val err = BufferedReader(InputStreamReader(p.errorStream)).use { it.readText() }
        val code = p.waitFor()
        arrayOf(code.toString(), out, err)
    }.getOrElse { e ->
        Log.w(TAG, "exec failed: $command", e)
        arrayOf("-1", "", e.message.orEmpty())
    }

    fun execBatch(commands: Array<String>): Int {
        var ok = 0
        for (cmd in commands) {
            // 逐条独立执行：某个 ROM 不认某条 appops 时不应中断其余命令。
            // 不因退出码非 0 而判定失败 —— 不同 Android 版本对未知 op 返回非 0，
            // 那是「不支持」而非「执行出错」。
            runCatching { exec(cmd) }.onSuccess { ok++ }
        }
        return ok
    }

    /**
     * Shizuku 通过 onBind 拿到 binder，进而调用 [exec] / [execBatch]。
     *
     * 注意这里返回的是 IShellService.Stub 的匿名实现，而不是本类自己 ——
     * Service 与 AIDL 接口必须分开：Service 是 Android 组件（无状态、可被系统重建），
     * AIDL Stub 是跨进程调用的入参。混在一起会让 AIDL 编译链困惑。
     */
    override fun onBind(intent: Intent?): IBinder = object : IShellService.Stub() {
        override fun exec(command: String): Array<String> = this@ShellService.exec(command)

        override fun execBatch(commands: Array<String>): Int = this@ShellService.execBatch(commands)
    }

    private companion object {
        const val TAG = "ShellService"
    }
}
