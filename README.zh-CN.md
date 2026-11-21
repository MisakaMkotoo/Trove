<p align="center"><img src="brand/icon-source.png" width="140" alt="Trove" /></p>

<h1 align="center">Trove</h1>

<p align="center"><b>Apple Music 风格的 Android 音乐播放器</b><br />本地曲库、网易云在线、电脑音乐库同步，一个应用里。</p>

<p align="center">
<img src="https://img.shields.io/badge/license-GPLv3-orange" alt="License: GPL-3.0" />
<img src="https://img.shields.io/badge/platform-Android%2012%2B-3DDC84?logo=android&logoColor=white" alt="Android 12+" />
<img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF" alt="Kotlin + Compose" />
</p>

<p align="center"><a href="README.md">English</a> · <a href="README.zh-CN.md">简体中文</a></p>

> [!IMPORTANT]
> Trove 是个人维护的非官方项目，与 Apple、网易云音乐均无关联，也未获得其授权或认可。
> 在线功能依赖第三方接口，可能随时失效；请优先使用正版服务。应用不包含账号、广告或统计上传。

## 功能

**界面**
- Apple Music 风格的玻璃与模糊界面，四种「正在播放」主题：标准、自适应取色、专辑封面流动背景（Kawarp 管线）、扭曲像素。
- 歌词弹簧滚动：活动行先行、远端行错峰拖尾，结尾行同样能回到焦点位。
- 可独立开启的「自适应字体颜色」，从封面取色，可与任意播放主题组合。
- 12 种界面语言，深浅色与「减少透明度」选项。

**播放**
- 队列播放：下一首播放、加入队列、拖动排序、移除；随机与循环。
- 迷你播放条与全屏播放器，进度、音量、音质标识。
- 内置 FFmpeg 解码：FLAC、MP3、M4A/AAC、OGG、Opus、WAV、AIFF、APE、WavPack、DSF/DFF、WMA 等。
- 10 段均衡器、音量标准化、最长 12 秒交叉淡入淡出。

**网易云**
- 通过歌单链接导入，匹配本地曲目或在线播放（不强制下载）。
- 首页每日推荐、私人雷达、心动模式。
- 在线歌词优先从网易云获取，失败回退 LRCLIB / 酷狗。
- 在线曲目可收藏，收藏单独持久化，并出现在「我喜欢的音乐」歌单。
- 在线播放走本地流缓存（800MB LRU），断网时退回暂停。

**本地曲库**
- 扫描设备音频文件，按曲目、艺人、专辑、流派、作曲家、播放列表浏览与搜索。
- 最近添加、重复曲目清理、隐藏 40 秒以下音频、底部标签页重排。
- 自定义播放列表封面与艺人图片；专辑封面取自内嵌图片。

**同步与导入**
- 局域网电脑音乐库同步。
- NCM 文件解码导入。
- Last.fm 记录（scrobbling）与听歌统计。

## 下载

从 [Releases](https://github.com/MisakaMkotoo/Trove/releases) 下载 APK。当前版本：**1.2.0 Beta**（`FavFoldMusic-v1.2.0-beta.apk`）。

要求 Android 12（API 31）及以上。首次安装需要在系统设置里允许来自该来源的安装。

## 构建

环境要求：

- JDK 21
- Android SDK 36（`compileSdk = 36`，`minSdk = 31`）
- Android NDK 30.x（以 `androidApp/build.gradle.kts` 的 `ndkVersion` 为准）
- 使用仓库自带的 Gradle wrapper

FFmpeg 的预编译 `.so` 不随仓库分发（`androidApp/src/main/jniLibs/` 已在 `.gitignore` 中）。首次构建前先生成：

```bash
bash scripts/build-ffmpeg-android.sh all
```

然后：

```bash
# 调试包
./gradlew :androidApp:assembleProdDebug

# 发布包（需要签名环境变量）
export MOBILE_KEYSTORE_FILE=/path/to/keystore
export MOBILE_KEYSTORE_PASSWORD=...
export MOBILE_KEY_ALIAS=...
export MOBILE_KEY_PASSWORD=...
./gradlew :androidApp:assembleProdRelease
```

未配置签名环境变量时会退回 debug 签名，仅适合本地测试。

## 项目结构

```text
androidApp/     Android 应用（Compose UI、播放服务、网易云、同步）
sharedLogic/    共享的播放队列与曲库模型
scripts/        FFmpeg / OpenSSL 构建脚本、图标与工具脚本
tools/          字体子集、罗马化数据
brand/          应用图标源文件
```

## 上游与致谢

Trove 是 [Exodi-dio/tune](https://github.com/Exodi-dio/tune)（GPL-3.0）的独立衍生项目，在播放器、界面、网易云集成与同步上做了大量修改，并非 Tune 的官方分支。

- 界面设计语言源自 [Airmedy](https://github.com/misa198/airmedy)（GPL-3.0）。
- 部分艺人页交互参考 [BitChord](https://github.com/kushagrasinghx/BitChord)（GPL-3.0）。
- 第三方组件与来源记录见 [NOTICE](NOTICE)。

## 许可证

本项目使用 **GNU General Public License v3.0（GPLv3）**，详见 [LICENSE](LICENSE)。作为 GPLv3 衍生作品，分发修改版时需保留版权声明与许可证，并提供对应源码。
