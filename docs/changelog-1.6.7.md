# Luonnotar 1.6.7

- Added the experimental GMS Binder Anchor.
- Added an entry point for requesting Luonnotar's own battery-optimization exemption.
- Fixed stale GMS Anchor callbacks contaminating a newer connection.
- Added Anchor PID, boot ID, and state-freshness validation.
- Fixed the independent 60-second timer for one-shot ADB passive verification.
- Continued the iQOO cooperative low-interference guardian strategy.

GMS Binder Anchor 为实验功能。显示已连接不代表 FCM/MCS 已确认，
也不保证所有 vivo/iQOO 系统都会停止冻结 GMS。
