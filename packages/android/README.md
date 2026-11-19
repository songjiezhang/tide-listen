# Any Listen 安卓试听版

手机和可直接安装 APK 的安卓车机分别使用 `phone` 与 `dashboard` 构建。两端共用午夜紫界面、Media3 后台播放器和曲库。车机版默认横屏，包名带 `.car`。最低系统为 Android 6.0，界面使用设备的 Android System WebView。

## 开始试听

1. 首页直接浏览内置 Online Metadata 提供的酷狗音乐、酷我音乐、网易音乐、企鹅音乐和咪咕音乐公开歌单。选择来源、打开歌单并浏览歌曲，或者把歌单收藏到「我的列表」。
2. 在「设置 → 音源 → 导入音源」可选择洛雪自定义 JS 文件，也可从链接导入。启用的音源依列表顺序解析兼容的在线歌曲。链接导入后可从原链接更新。
3. 在「设置 → WebDAV → 配置」填写桌面版使用的服务器地址加同步路径，指向包含 `list/` 的目录。使用支持 WebDAV 读写的账号，新建、改名、收藏和移除歌曲等操作会自动同步到同一份歌单。网络异常时修改保存在当前设备，重新打开应用或恢复网络后会重试。只读账号可浏览远端歌单，尚未提交的修改会显示为待同步。
4. 「我的列表」中包含「我喜欢」和「试听列表」。可从播放条点爱心收藏，点歌曲标题或封面打开播放界面。此前版本的安卓在线曲库和收藏会在升级时导入相应列表。本地音频只在选中文件的设备上播放。
5. 完整播放界面使用内置 Online Metadata 获取在线歌曲的同步歌词。手机在封面下方显示歌词，车机横屏将封面和控制放在左侧，歌词放在右侧。
6. 车机播放条额外提供播放模式按钮，默认在前台播放时保持亮屏。可在「设置 → 播放与显示」更改行为。

内置歌单和歌词模块取自官方 [Online Metadata v0.4.9](https://github.com/any-listen/any-listen-extension-online-metadata/tree/v0.4.9)。如需重新生成 Android 使用的子集，可在该版本源码安装 `he@1.2.0` 后，运行 `bash tools/bundle-online-metadata.sh <扩展源码目录> <esbuild的绝对路径>`。随包附有 Online Metadata 与 he 的许可证。

## 构建

准备 JDK 17、Android SDK Platform 35 和 Build Tools 35。在本目录执行：

```sh
./gradlew assemblePhoneDebug assembleDashboardDebug lintPhoneDebug lintDashboardDebug
```

Windows 可使用 `gradlew.bat`。APK 分别生成在 `build/outputs/apk/phone/debug/` 和 `build/outputs/apk/dashboard/debug/`。这些构建使用本机 Android 调试签名。

## 应用内更新

0.11.0 起支持「设置 → 关于 → 检查更新」。手机和车机使用各自的 APK，系统会验证同包名应用的签名。发布时分别构建 `assemblePhoneRelease`、`assembleDashboardRelease`，使用上一版对应的签名密钥为 Release APK 签名，并上传到同一 GitHub Release。安装包按主 README 的命名规则发布。
