package com.dsh.aihub

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.dsh.aihub.shizuku.IShellService
import com.dsh.aihub.shizuku.ShellService
import rikka.shizuku.Shizuku

/**
 * 借助 Shizuku 一次性完成保活所需的系统级放行。
 *
 * 为什么需要：本项目的保活原本靠用户手动去系统设置逐项打开（见 [KeepAliveGuide]），
 * 用户要自己找 5~7 个开关、不同厂商路径还都不一样。有 Shizuku 后这些
 * appops 可以用 shell 命令直接放行。
 *
 * 为什么用 Shizuku 而不是 su：不需要设备 root，覆盖面大得多；授权一次性，
 * 之后长期有效；走官方 API，不依赖各机型的 su 实现。
 *
 * 注意：Shizuku **不保证**进程不被杀。它只是让我们能改系统设置。真正的保活
 * 仍是「前台服务 + 悬浮窗 + 电池白名单」的组合，这一层把后者自动化。
 */
object ShizukuKeeper {

    private const val TAG = "ShizukuKeeper"
    private const val REQ_CODE = 4001

    /** UserService 的 binder 代理。null = 尚未连接。 */
    @Volatile
    private var shell: IShellService? = null

    private var connection: ServiceConnection? = null
    private var serviceArgs: Shizuku.UserServiceArgs? = null

    /** Shizuku 服务是否已安装并正在运行。 */
    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** 是否已拿到本应用的授权。 */
    fun hasPermission(): Boolean = runCatching {
        if (!isRunning()) return false
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * 申请授权并连接 UserService。
     *
     * 只在有界面的 Activity 里调用 —— Shizuku 的确认框需要 Activity 承接。
     * @return true 表示已授权且服务已开始连接
     */
    fun requestPermissionAndBind(activity: Activity): Boolean {
        if (!isRunning()) {
            Log.i(TAG, "Shizuku 不可用（未安装或服务未启动）")
            return false
        }
        if (!hasPermission()) {
            runCatching { Shizuku.requestPermission(REQ_CODE) }
                .onFailure { Log.w(TAG, "requestPermission failed", it) }
            // 用户点「允许」后会触发 OnRequestPermissionResult，
            // 由调用方在回调里再次调用本方法完成绑定。
            return false
        }
        bindService(activity)
        return true
    }

    /** 绑定 UserService。已绑定则跳过。 */
    private fun bindService(context: Context) {
        if (shell != null) return
        runCatching {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, ShellService::class.java.name)
            )
                .daemon(false)      // 不常驻守护：只在需要时拉起，减少常驻内存
                .processNameSuffix("shell")
                .debuggable(false)
                .version(1)

            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (binder == null || !binder.pingBinder()) {
                        Log.w(TAG, "user service binder dead")
                        return
                    }
                    shell = IShellService.Stub.asInterface(binder)
                    Log.i(TAG, "user service connected")
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    shell = null
                    Log.i(TAG, "user service disconnected")
                }
            }
            connection = conn
            serviceArgs = args
            Shizuku.bindUserService(args, conn)
        }.onFailure { Log.w(TAG, "bindUserService failed", it) }
    }

    /** 解绑 UserService（退出保活/关闭开关时调用）。 */
    fun unbind() {
        val conn = connection ?: return
        val args = serviceArgs
        if (args != null) {
            runCatching { Shizuku.unbindUserService(args, conn, true) }
                .onFailure { Log.w(TAG, "unbind failed", it) }
        }
        shell = null
        connection = null
        serviceArgs = null
    }

    /**
     * 一次性完成保活相关的系统放行。
     *
     * 全部命令幂等（allow 重复执行无副作用），可反复调用。
     * 每条独立执行 —— 某个 ROM 不支持某条 appops 时不影响其余。
     *
     * @return 成功执行的条数；-1 表示 shell 服务未就绪
     */
    fun applyKeepAlive(context: Context): Int {
        val svc = shell ?: run {
            Log.i(TAG, "applyKeepAlive: shell 服务未就绪，先请求授权")
            if (hasPermission()) bindService(context)
            return -1
        }
        val pkg = context.packageName
        val cmds = arrayOf(
            // 后台运行限制
            "cmd appops set $pkg RUN_IN_BACKGROUND allow",
            "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow",
            // 唤醒与闹钟：定时签到依赖 AlarmManager
            "cmd appops set $pkg WAKE_LOCK allow",
            "cmd appops set $pkg SCHEDULE_EXACT_ALARM allow",
            // 前台服务：Android 14+ 对 specialUse 有额外限制
            "cmd appops set $pkg START_FOREGROUND allow",
            // 后台启动 Activity：部分 ROM 需要，否则从通知点回应用会失败
            "cmd appops set $pkg START_ACTIVITIES_FROM_BACKGROUND allow",
            // 电池优化白名单：保活里最关键的一条
            "dumpsys deviceidle whitelist +$pkg",
        )
        val n = runCatching { svc.execBatch(cmds) }
            .onFailure { Log.w(TAG, "execBatch failed", it) }
            .getOrDefault(0)
        Log.i(TAG, "applyKeepAlive: $n/${cmds.size} 条已执行")
        return n
    }

    /** 撤销放行，恢复系统默认。只回滚最关键的几项。 */
    fun revert(context: Context): Int {
        val svc = shell ?: return -1
        val pkg = context.packageName
        val cmds = arrayOf(
            "cmd appops set $pkg RUN_IN_BACKGROUND default",
            "cmd appops set $pkg RUN_ANY_IN_BACKGROUND default",
            "cmd appops set $pkg WAKE_LOCK default",
            "cmd appops set $pkg START_FOREGROUND default",
            "dumpsys deviceidle whitelist -$pkg",
        )
        return runCatching { svc.execBatch(cmds) }.getOrDefault(0)
    }

    /** 供 UI 展示的状态。 */
    data class Status(val running: Boolean, val granted: Boolean, val serviceBound: Boolean) {
        val summary: String
            get() = when {
                !running -> "Shizuku 未运行"
                !granted -> "已连接，待授权"
                !serviceBound -> "已授权，服务连接中"
                else -> "已就绪"
            }
    }

    fun status(): Status = Status(
        running = isRunning(),
        granted = hasPermission(),
        serviceBound = shell != null,
    )
}
