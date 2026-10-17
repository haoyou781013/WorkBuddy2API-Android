// Shizuku UserService 接口：在 Shizuku 拉起的进程（shell/root 权限）里执行命令。
//
// 为什么是 AIDL 而非普通 interface：UserService 跑在独立进程，
// 跨进程调用必须有 AIDL 生成的 Stub/Proxy。
// 为什么需要它：Shizuku.newProcess 在 13.x 已收回 private，
// 官方推荐做法就是自己起一个 UserService。
package com.dsh.aihub.shizuku;

interface IShellService {
    /** 执行 shell 命令，返回 [exitCode, stdout, stderr]。 */
    String[] exec(String command);

    /** 批量执行，返回成功条数。命令之间互不影响。 */
    int execBatch(in String[] commands);
}
