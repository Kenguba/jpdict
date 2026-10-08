# Android Release Signing

该目录只保存可公开的签名证书信息，不保存私钥。

- Alias: `jpdict`
- Algorithm: RSA 4096 / SHA256withRSA
- Certificate SHA-256: `CB:8B:97:98:9B:FE:78:94:E7:96:84:2C:E5:B2:E3:E3:B2:AA:B4:8A:A7:F0:F7:A9:7C:1E:CE:04:AC:18:42:52`
- Certificate SHA-1: `57:8E:6C:23:BE:C6:CA:9B:B4:42:D0:DA:B9:AC:66:DE:28:4D:2E:5B`
- Public certificate: `jpdict-release-cert.pem`

私钥文件 `.jks`、Store Password 和 Key Password 必须保存在安全位置，并通过 GitHub Actions Repository Secrets 提供给构建流程。

需要的 Secrets：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

不要把 JKS 或密码提交到公开仓库。
