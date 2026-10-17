# 开发约定

本文件是本仓库的硬约束，不是建议。

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

### 2.3 累计多个修复再发 patch

单个小 bug 修完先 commit，攒到有实际意义的量再发。
判断标准：**用户能否感知到差异**。感知不到就不发。

## 三、代码约定

- 新增逻辑先看有没有现成组件可复用（`m3/` 与 `ui/PcComponents.kt`）
- 改动共享组件前先查依赖方，避免连锁影响
- 关键决策写进注释说明「为什么」，不是「做了什么」
- 不引入体积大的依赖；图标用内联 `ImageVector`，不引入 `material-icons-extended`
- `NavCompat.kt` 必须保留（Compose Dialog 的 dispatcher 查找缺陷，与 UI 库无关）

## 四、构建

本机 proot 可直接编译，不依赖 GitHub Actions：

```bash
/opt/android-sdk/build.sh debug     # 或 release
```

`aapt2` 死锁的解决方案见 `/opt/android-sdk/aapt2-proxy.py` 头注释（四层，
缺一不可）。本地产物与 CI 产物逐字节一致。

## 五、已知限制

- Release APK 目前使用 debug 签名（未配 keystore），**不可正式分发**
- 保活修复依赖用户手动授权「电池优化白名单」，未经真机长期验证
