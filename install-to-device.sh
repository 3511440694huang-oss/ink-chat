#!/system/bin/sh
# 安装 ink-chat debug APK 到本机
# 在 Android Shell（Shizuku / Root）环境执行：sh install-to-device.sh
# SELinux 注意：不可直接 pm install fuse 下的 APK，须先拷进 /data/local/tmp
APK='/storage/emulated/0/WORK/01-开发项目/Deepseek墨水屏/ink-chat/app/build/outputs/apk/debug/app-debug.apk'
cp "$APK" /data/local/tmp/ink-debug.apk && pm install -r /data/local/tmp/ink-debug.apk