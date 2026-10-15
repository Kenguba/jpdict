# JPDict Android 构建、签名与交付

> 仅在 Android 构建/发布任务时读取。与 [版本契约](versioning.md) 配合使用；执行前确认当前 CI 文件的实际行为，不能按旧示例推断已经发布。

## Android CPU 架构 / ABI 说明

这里的 v7、v8 指的是 Android 设备的 CPU 指令集架构（ABI），不是 Android 系统版本。

| ABI | 简称 | 位数 | 主要设备 / 用途 |
| --- | --- | --- | --- |
| `armeabi-v7a` | v7 | 32 位 | 老 Android 手机、旧平板 |
| `arm64-v8a` | v8 | 64 位 | 现在绝大多数 Android 手机 |
| `x86` | x86 | 32 位 | 老模拟器、少量旧设备 |
| `x86_64` | x86_64 | 64 位 | 模拟器、部分 Chromebook / 特殊设备 |

## APK 下载建议

正式发布 APK 必须同时满足上面的版本化命名规则和 **单 APK ≤ 30 MiB** 的体积硬限制。

| 架构 / 变体 | 包含架构 | 建议 |
| --- | --- | --- |
| `arm64-v8a` | `arm64-v8a` | 推荐给现在绝大多数 Android 手机，体积最小 |
| `armeabi-v7a` | `armeabi-v7a` | 老旧 32 位 ARM 手机 / 平板 |
| `arm` | `arm64-v8a + armeabi-v7a` | 手机通用集合版，兼容新旧 ARM 手机 |
| `universal` | `arm64-v8a + armeabi-v7a + x86 + x86_64` | 仅在最终文件 ≤ 30 MiB 时允许发布 |

普通用户建议：

- 新款 Android 手机：下载 `arm64-v8a`
- 不确定新旧 ARM 架构：下载 `arm`
- 老旧 32 位设备：下载 `armeabi-v7a`
- 模拟器 / Chromebook / 特殊设备：优先使用对应 ABI 构建；不得为了兼容而发布超过 30 MiB 的 universal APK。

> `arm64-v8a` 和 `armeabi-v7a` 是当前手机端最重要的两种 ARM ABI。x86 / x86_64 如需支持，应继续采用分架构产物或控制在 30 MiB 上限内。

## 固定 Release 签名

正式 APK 使用固定 Release 证书。公开证书保存在：

`signing/jpdict-release-cert.pem`

证书 SHA-256：

`CB:8B:97:98:9B:FE:78:94:E7:96:84:2C:E5:B2:E3:E3:B2:AA:B4:8A:A7:F0:F7:A9:7C:1E:CE:04:AC:18:42:52`

私钥 / JKS 不提交到仓库。GitHub Actions 读取以下 Repository Secrets：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

如果这 4 个 Secrets 尚未配置，Workflow 只生成临时 Debug Artifact，不发布正式 Release。Secrets 配好后，重新运行 Workflow 即可生成固定签名的正式 APK。

> 注意：GitHub Actions 的具体实现必须服从[versioning.md](versioning.md) 的版本与 APK 命名规则。Android `versionCode` 仍需使用独立的纯数字永久递增序列。

## 本地构建

为控制 APK 体积，默认只构建当前可发布的 3 个变体；universal 不作为默认发布产物。

Debug：

```bash
gradle --no-daemon \
  :app:assembleArmDebug \
  :app:assembleArm64Debug \
  :app:assembleArmv7Debug
```

正式 Release 构建需要先提供固定签名环境变量，然后运行：

```bash
gradle --no-daemon \
  :app:assembleArmRelease \
  :app:assembleArm64Release \
  :app:assembleArmv7Release
```

GitHub Actions 会自动重命名 APK、验证签名、计算 SHA-256、上传 Artifact，并创建 GitHub Release。发布步骤最多重试三次，显式发布草稿；必须通过按标签读取正式 Release 的接口，核对当前提交、正式发布状态及三个 APK/校验文件均上传完整后才算成功，不能仅依据 CLI 返回的草稿链接。所有产物名称和版本号必须遵守[versioning.md](versioning.md) 的规则。

推送到 `main` 时使用 `R` 正式发行阶段；手动运行 Workflow 时仍可选择其他阶段。


## 当前 CI 注意事项

- [现有 GitHub Actions](../../../../.github/workflows/build-jpdict.yml) 对每次 `main` 的 push 触发，且 **有签名 Secrets 时会创建正式 Release**；手动 `workflow_dispatch` 也会进入相同的签名/发布路径，不能当成纯构建命令。
- CI 使用 `scripts/allocate-build-version.py` 参考 `version.properties`、已有 tags / Releases 与 Workflow 流水号分配版本，推送时阶段默认为 `R`；不擅自手工覆盖版本或复用已有标签。
- CI 依次执行项目自有测试、三个架构 Lint、Release/Debug 构建、35 MiB 源码和单 APK 30 MiB 审核、四脚本离线 OCR 模型校验、固定签名/包名/版本号、SHA-256 和产物完整性检查。
- 若四个签名 Secrets 未配置，则只产生 Debug Artifact，不会正式发布。不要读取或输出私钥、JKS、密码；不要把 Debug APK 当正式产物。
- CI 使用 [发布说明](../../../../docs/release-notes.md)，签名身份公开信息见 [signing/README.md](../../../../signing/README.md)。正式 Release 必须核对标签、对应源码 SHA、三个 ABI APK、校验清单及完整上传状态。
- **仅需构建或验证**时，优先使用原有本地 Gradle 检查及仅构建命令；在没有防止发布的明确路径时，不触发会自动发布的 CI。非发布 push 的防触发策略须服从仓库检查与权限要求。
