# Any Listen Midnight

基于 [Any Listen](https://github.com/any-listen/any-listen) 的个人非商业改版，由 songjiezhang 维护。Windows 桌面端沿用上游的播放器和扩展机制。Android 手机和横屏车机使用独立的 Media3 客户端，三端可通过 WebDAV 共用歌单。

## 下载安装

从 [Releases](https://github.com/songjiezhang/mylisten-midnight/releases/latest) 选择对应安装包。

| 设备 | 安装包文件名 | 软件内更新入口 |
| --- | --- | --- |
| Windows x64 | `Any-Listen-Midnight-版本-x64-Setup.exe` | 设置 → 软件更新，打开更新窗口 |
| Android 手机 | `Any-Listen-Midnight-Phone-版本.apk` | 设置 → 关于 → 检查更新 |
| Android 横屏车机 | `Any-Listen-Midnight-Car-版本.apk` | 设置 → 关于 → 检查更新 |

Android 最低支持 6.0。车机需要支持直接安装 APK。首次从旧测试版升级请手动安装对应版本。从 0.11.0 开始，后续版本可在软件内下载和安装。Android 8.0 及以上首次应用内更新时，请在系统页面允许该应用安装更新，再返回播放器。Windows 安装包没有 Authenticode 签名，Release 附有 SHA-256 校验值。

## 播放与歌单

- 桌面端使用 Midnight 圆角主题，支持按链接更新已导入的洛雪 JS 音源，组合启用音源并按顺序尝试播放。桌面在线歌单和歌词通过扩展管理器获得。
- Android 首页内置 Online Metadata v0.4.9 的歌单模块，可浏览酷狗、酷我、网易、企鹅和咪咕的公开歌单。首页先显示缓存，再后台刷新。播放页使用同一扩展获取同步歌词。
- 在 Android 设置中填写桌面端使用的 WebDAV 同步目录，即可按原名称查看「我的列表」。添加、收藏、改名等变更会自动同步。播放网络歌曲所需的 JS 音源由使用者自行从有权使用的来源导入。
- 手机重新打开后恢复上次曲目、队列和进度。车机默认打开后继续播放，横屏左侧为封面和控制，右侧为滚动歌词。

## 从源码构建

请检出与安装包一致的 Release 标签。桌面端需要 Node.js 22.12 或更高版本，以及项目根目录 `packageManager` 指定的 pnpm 10。在 Windows x64 的干净工作副本中执行：

```sh
pnpm install --frozen-lockfile
pnpm build:desktop
pnpm -F desktop pack:win:setup:x64
```

Windows 安装包位于根目录 `build/`。Android 使用 JDK 17 和 Android SDK 35，在 `packages/android` 下运行 `./gradlew assemblePhoneRelease assembleDashboardRelease`（Windows 使用 `gradlew.bat`）。Release APK 需使用自己的私钥签名，个人签名构建可通过同一密钥覆盖升级。详见 [Android 说明](packages/android/README.md)。

发布稳定版使用 `v主版本.次版本.修订号` 标签。提高桌面和 Android 的版本号，同时递增 Android versionCode。同一个 Release 应包含三个安装包、electron-builder 的 `latest.yml`、安装包对应的 `.blockmap` 和校验文件。Windows 从发布资料读取版本与 SHA-512，Android 从 GitHub Release 读取对应 APK 的大小和 SHA-256。

## 上游、改动与许可证

本项目是独立改版。原项目版权及署名归 Any Listen 原作者和贡献者。此次修改日期为 **2026-11-20**，基线为 [Any Listen 提交 8f6e746](https://github.com/any-listen/any-listen/commit/8f6e74641c708219e6933041a93bf2750ed9b1f2)。改动包括 Midnight 桌面界面、音源管理、Android 手机与车机客户端及软件内更新。标签对应完整源码和构建脚本。

沿用上游的 [自定义许可证](LICENSE)，即 AGPL v3 正文及非商业附加条款。未经原作者明确书面许可，禁止商业使用及直接或间接创收。本项目免费分发，无广告、收费服务或捐赠入口。软件按现状提供。音乐、歌词、封面及第三方音源的权利归各自权利人。

Android 适配了 [Online Metadata v0.4.9](https://github.com/any-listen/any-listen-extension-online-metadata/tree/v0.4.9) 和 [LX Music Mobile](https://github.com/lyswhut/lx-music-mobile) 的部分代码，两者使用 Apache-2.0。LX Music Mobile 同时列有[项目补充条款](https://github.com/lyswhut/lx-music-mobile#项目协议)。实体解码库 he 使用 MIT 许可。相关声明位于 [Android 第三方许可](packages/android/src/main/assets/licenses)。
