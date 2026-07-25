# 努昂诺塔 Release 签名备份

正式版本使用独立的 4096 位 RSA Release 密钥，不再使用 Android Debug 密钥。

必须一起离线备份：

- `signing/luonnotar-release.jks`
- `keystore.properties`

两者均已加入 `.gitignore`。丢失密钥或密码后，将无法为同一 Application ID
发布可覆盖安装的后续版本。不要把这两个文件上传到公开仓库或随 APK 分发。

当前证书 SHA-256：

`C5:5B:8E:45:40:F0:2A:B2:33:8B:75:3A:86:1F:4F:E6:45:3B:30:16:92:35:D7:FD:74:E1:0C:B3:D5:A2:44:3E`
