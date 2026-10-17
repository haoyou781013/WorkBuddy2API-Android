# 开发约定

本文件是本仓库的硬约束，不是建议。

## 零、约束优先级

发生冲突时，按以下顺序遵从，**上位约束优先**：

1. **原项目要求**（jilin0105/WorkBuddy2API-Android 的免责声明）
2. **上游使用声明**（workbuddy2api-panel README）
3. **开源协议**（MIT）
4. 本项目自身约定

### 原项目的首要要求（原文）

> **本项目仅供学习与技术研究使用，请勿用于任何商业用途。**
>
> - 仅用于学习 API 网关原理、协议适配、Android 原生开发等技术
> - **禁止**用于商业用途、生产环境、批量调用、代理转售，或任何违反
>   WorkBuddy / CodeBuddy 服务条款的行为
> - 使用者须自行遵守上游服务条款，**自行承担全部使用风险**
> - 作者不对任何因使用本项目产生的直接或间接损失负责

原项目还明确：**本仓库为本地自用工具，不发布应用商店**。

**这三条比 MIT 更严格，MIT 给了再分发的自由，但原项目的要求限定了
使用场景。派生版本必须一并继承，不得因为「MIT 允许」就放宽。**

本项目同样以 **个人免费使用、不商用、不上架** 为前提发布。

## 一、开源协议与作者权益

### 1.1 本项目按 MIT 协议派生

派生自 [jilin0105/WorkBuddy2API-Android](https://github.com/jilin0105/WorkBuddy2API-Android)，
该仓库又参考了以下 Python 实现：

- [wicm84266964/Buddy2api](https://github.com/wicm84266964/Buddy2api)
- [ShouZhuo0413/codebuddy2api](https://github.com/ShouZhuo0413/codebuddy2api)
- [hawklithm/workbuddy2api](https://github.com/hawklithm/workbuddy2api)

**MIT 要求在所有副本中保留原始版权声明。** 因此：

- `Attribution.kt` 是来源信息的唯一事实源，改地址只改那里
- 应用内「关于」页必须展示原项目地址（用户拿到的是 APK，看不到仓库）
- 新增文件若包含来自上游的代码，必须在文件头注明出处

### 1.2 不改上游协议

本 fork 保持 MIT，**不额外增加**商业限制条款、license key 校验、
联网激活等与协议冲突的限制。

### 1.3 上游作者的使用限制（必须传递）

上游 `workbuddy2api-panel` 的 README 明确声明，以下行为被**强烈反对**：

- 批量注册小号 / 收购账号，对外提供付费 API、共享池、代充业务
- 二次加壳、捆绑卡密（授权码）售卖
- 以「公益服」「低价中转」等名义变相收费分发
- 购买或分发任何「收费版」「卡密版」

理由：这些行为违反目标平台服务条款，可能致账号封禁；且加壳分发无法审计，
`auths/` 中保存明文 accessToken / refreshToken，存在被植入后门的风险。

**这些限制必须出现在应用内「关于」页**，不能只写在 README——否则
纯 APK 用户与作者声明脱节。对应实现在 `Attribution.USAGE_NOTICE`。

MIT 赋予的是代码层面的自由，**不赋予**以本项目名义宣传、售卖、捆绑分发的权利。

## 二、发布节奏

### 2.1 一批功能做完才发一个版

中间改动**只 commit，不 tag、不发 Release**。理由：
- 每个 tag 都会进客户端的版本列表（更新页扫描全部版本）
- 频繁发版让版本列表变成调试流水，用户无从判断该装哪个
- 未验证的改动发出去，用户成了免费的测试环境

### 2.2 发布前置条件

三件事全部满足才发版：

1. **本地构建通过**：`/opt/android-sdk/build.sh release`
2. **关键行为已验证**：改动涉及的功能实际跑过一遍
3. **版本号符合 SemVer**：

   | 改动性质 | 版本位 | 例 |
   |---|---|---|
   | 不兼容变更 | major | 1.x.x → 2.0.0 |
   | 新增功能（向下兼容） | minor | 1.1.x → 1.2.0 |
   | 修 bug（向下兼容） | patch | 1.1.0 → 1.1.1 |
   | 仅内部重构 / 文档 / 构建脚本 | **不发版** | — |

   同一批功能已经发过版就再发一次时，用 patch 位。
   需要先给别人试的用测试版后缀（见 2.3）。

### 2.3 测试版与正式版

发布时用 GitHub Release 的 **`prerelease` 标记**区分两个通道：

| 通道 | 标记 | 版本号 | 客户端默认 |
|---|---|---|---|
| 正式版 | `prerelease: false` | `v1.2.0` | 显示 |
| 测试版 | `prerelease: true` | `v1.2.0-beta.1` | **不显示** |

规则：

- **测试版用 `-beta.N` / `-rc.N` 后缀**，SemVer 规定预发布段低于同号正式版，
  所以 `v1.2.0-beta.1` < `v1.2.0`，正式版发出来后客户端会自动认为该升级
- **测试版不进稳定通道**。客户端默认 `updateChannel=stable`，
  只有用户在「软件更新」里手动打开「接收测试版」才会看到
- **测试版可以不满足 2.2 的验证要求**（本来就是给人试的），
  但必须在上传前本地构建通过
- 正式版必须满足 2.2 全部三条

发版命令示例（GitHub API）：

```bash
# 测试版
curl -X POST .../releases -d '{"tag_name":"v1.2.0-beta.1","prerelease":true,...}'
# 正式版
curl -X POST .../releases -d '{"tag_name":"v1.2.0","prerelease":false,...}'
```

### 2.4 累计多个修复再发 patch

单个小 bug 修完先 commit，攒到有实际意义的量再发。
判断标准：**用户能否感知到差异**。感知不到就不发。

## 三、代码约定

- 新增逻辑先看有没有现成组件可复用（`m3/` 与 `ui/PcComponents.kt`）
- 改动共享组件前先查依赖方，避免连锁影响
- 关键决策写进注释说明「为什么」，不是「做了什么」
- 不引入体积大的依赖；图标用内联 `ImageVector`，不引入 `material-icons-extended`
- `NavCompat.kt` 必须保留（Compose Dialog 的 dispatcher 查找缺陷，与 UI 库无关）

## 四、构建与签名

### 4.1 签名必须固定（血泪教训）

**必须用固定的 release keystore，不能用 debug 签名。**

第 1 版用了 `signingConfig = debug`，结果：debug keystore 由构建机自动生成，
本机与 CI 生成的是不同证书（`d43e6bb1...` vs `71841537...`），
系统判定为不同应用 → **更新时报「签名冲突」拒绝覆盖安装**。

本项目的确不上架商店，但**应用内更新必须能覆盖安装**，
所以签名必须跨构建、跨机器稳定。

- keystore：`/root/keys/aihub-release.jks`（别名 `aihub`，RSA 4096，有效期至 2054）
- 证书 SHA-256：`03:B8:5B:8F:C1:BB:DE:FF:33:4A:D1:41:D2:3F:F8:F5:7D:DA:0D:25:01:3F:E5:89:BB:3E:1F:8B:7F:02:FB:1D`
- 配置文件：`~/.android/aihub-signing.properties`（mode 600，已在 .gitignore）
- 备份：`/root/keys/backup/`，另有加密包 `/tmp/aihub-signing-backup.tar.gz.enc`
  （口令同 storePassword）

构建脚本按此优先级读取签名材料：
1. 环境变量 `AIHUB_STORE_FILE` / `AIHUB_STORE_PASSWORD` / `AIHUB_KEY_ALIAS` / `AIHUB_KEY_PASSWORD`
2. `~/.android/aihub-signing.properties`
3. 都没有 → 退回 debug 签名，并在构建时打 WARNING（**产物不可用于发布**）

> ⚠️ **密钥丢失 = 永远无法发布能覆盖安装的更新。** 只能让用户卸载重装。

### 4.2 CI 只做编译检查，不产出 APK

`.github/workflows/build.yml` 只跑 `compileDebugKotlin` + `compileReleaseKotlin`，
权限为 `contents: read`。

原因：APK 必须用上面的固定 keystore 签名。若 CI 从 Secrets 取密钥，
等于把签名权搬上云端；本项目是个人自用工具，构建与签名都留在本机
更简单、更好审计。CI 的价值只剩「别把编译错误留到发版时」这一件事。

### 4.3 本地构建

本机 proot 可直接编译，不依赖 GitHub Actions：

```bash
/opt/android-sdk/build.sh debug     # 或 release
```

`aapt2` 死锁的解决方案见 `/opt/android-sdk/aapt2-proxy.py` 头注释（四层，
缺一不可）。本地产物与 CI 产物逐字节一致。

## 五、已知限制

- 保活修复依赖用户手动授权「电池优化白名单」，未经真机长期验证
- 签名已改为固定 keystore；但 v1.0.0 ~ v1.1.1 是 CI 的 debug 签名，
  **无法用新签名覆盖安装，必须卸载重装一次**（一次性代价）
- 应用内更新需手动开启「接收测试版」才能看到预发布版本
