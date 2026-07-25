# 努昂诺塔 Release 签名备份

正式版本使用独立的 4096 位 RSA Release 密钥，不再使用 Android Debug 密钥。

必须一起离线备份：

- `signing/luonnotar-release.jks`
- `keystore.properties`

两者均已加入 `.gitignore`。丢失密钥或密码后，将无法为同一 Application ID
发布可覆盖安装的后续版本。不要把这两个文件上传到公开仓库或随 APK 分发。

当前证书 SHA-256：

`48:0D:7F:A7:1D:DD:D9:9D:DB:10:AA:00:F8:77:D9:C6:D7:EA:87:F1:4B:2E:08:D1:AB:34:CF:DE:A5:63:1C:50`
