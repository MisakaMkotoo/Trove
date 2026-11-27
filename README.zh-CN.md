<h1 align="center">Trove</h1>

<p align="center"><b>网易云音乐第三方 Android 客户端 · 本地音乐播放器</b><br />在线流媒体、本地曲库、电脑音乐库同步，一个应用里。</p>

<p align="center">
<img src="https://img.shields.io/badge/license-GPLv3-orange" alt="License: GPL-3.0" />
<img src="https://img.shields.io/badge/platform-Android%2012%2B-3DDC84?logo=android&logoColor=white" alt="Android 12+" />
<img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF" alt="Kotlin + Compose" />
<img src="https://img.shields.io/badge/version-1.2.0--beta-blue" alt="Version 1.2.0-beta" />
</p>

<p align="center"><a href="README.md">English</a> · <a href="README.zh-CN.md">简体中文</a></p>

> [!IMPORTANT]
> Trove 是个人维护的非官方项目，与网易云音乐、Apple 均无关联，也未获得其授权或认可。
> 在线功能依赖第三方接口，可能随时失效；请优先使用正版服务。
> 应用无广告、无统计上报；除网易云接口本身外不向任何服务器上传数据，登录凭据只保存在本机。

## 界面预览

<table align="center">
  <tr>
    <td align="center"><img src="assets/screenshots/home.jpg" width="240" alt="主页" /><br /><sub><b>主页</b></sub></td>
    <td align="center"><img src="assets/screenshots/discover.jpg" width="240" alt="新发现" /><br /><sub><b>新发现</b></sub></td>
    <td align="center"><img src="assets/screenshots/library.jpg" width="240" alt="资料库" /><br /><sub><b>资料库</b></sub></td>
  </tr>
  <tr>
    <td align="center"><img src="assets/screenshots/search.jpg" width="240" alt="搜索界面" /><br /><sub><b>搜索界面</b></sub></td>
    <td align="center"><img src="assets/screenshots/lyrics.jpg" width="240" alt="歌词界面" /><br /><sub><b>歌词界面</b></sub></td>
    <td align="center"><img src="assets/screenshots/now-playing.jpg" width="240" alt="播放界面" /><br /><sub><b>播放界面</b></sub></td>
  </tr>
</table>

## 功能

### 网易云音乐

- **登录与协议**：支持扫码、手机号 + 密码、短信验证码与网页登录；请求默认走官方 Android `eapi` 指纹，可在设置里切换网页兼容模式。
- **首页与电台模式**：每日推荐（支持手动刷新）、私人雷达与多雷达卡片、推荐歌单、动态首页分区与关注歌手新歌；内置 **私人漫游（FM，支持不喜欢）**、**心动模式** 与 **相似歌曲模式** 自动续播。
- **发现与广场**：排行榜、甄选歌单、新歌新碟，以及分类发现广场（歌单分类、精品歌单、排行榜、新碟上架、热门歌手）。
- **搜索**：支持在「网易云在线（单曲 / 歌单 / 专辑 / 歌手）」与「本地曲库」之间直接切换；提供热搜、猜你喜欢、实时联想与搜索历史管理。
- **歌单、专辑与艺人**：链接导入歌单、创建/编辑/删除自建歌单、歌单加减曲目与拖动排序、公开/隐私设置、收藏歌单与专辑、关注歌手；歌手详情分页展示歌曲与专辑，支持相似歌曲与热门/最新评论浏览。
- **云盘、足迹与喜欢**：云盘曲目浏览与删除、云端听歌足迹（最近 / 周榜 / 总榜）、可配播放历史上传开关；在线「喜欢」与本地收藏独立，自动汇总至「我喜欢的音乐」。
- **在线播放与高清封面**：进程内 `FFmpeg AVIOContext` 流式取流配合可调上限的本地流缓存，断网自动暂停并在网络恢复后原地续传；试听片段照常播放并给出提示，取流失败按原因分类并有限重试，全屏页懒加载 4000×4000 高清封面并缓存。
- **下载与 Wi-Fi 自动缓存**：7 档音质（标准、极高、无损、Hi-Res、高清臻音、沉浸环绕声、超清母带）自动回退，支持应用私有目录或通过 SAF 自选保存文件夹、通知栏进度与一键整单下载，以及不计费 Wi-Fi 下的歌单自动缓存管理。

### 本地曲库与整理

- **扫描与浏览**：扫描设备音频并支持单目录白名单限制；按歌单、艺人、专辑、歌曲、多级文件夹、流派、作曲家（流派/作曲家可在设置里显隐）与「最近添加」浏览，配备字母快速索引与下拉即时文字过滤。
- **清理与匹配**：重复曲目检测清理、一键隐藏 40 秒以下短音频、手动隐藏与隐藏曲目管理、直接从设备删除文件，以及本地曲目匹配网易云歌曲信息。
- **智能歌单**：按标题、艺人、专辑、时长、格式、播放次数、收藏状态与文件夹路径自由组合条件，动态生成歌单。
- **元数据与导入导出**：查看音频技术参数（编码器、采样率、位深、声道/码率），支持自定义歌单封面与歌手头像；支持 **M3U 歌单导入与导出**、**NCM 加密文件解码与导入**（保留标签与内嵌封面）。
- **多选与导航定制**：长按/多选操作栏批量加入队列、收藏、加歌单或移除；支持将「我的」与「资料库」合并为双 Tab 单页，底部标签页支持显隐、排序、记住上次子页面与「滑动时合并底栏和迷你播放条」。

### 播放引擎与音效

- **自研 FFmpeg + AAudio JNI 内核**：原生解码 FLAC、MP3、M4A/AAC、OGG、Opus、WAV、AIFF、APE、WavPack、DSF/DFF、WMA 等格式；完善处理 Media3 媒体会话、音频焦点、拔耳机暂停及输出路由断开恢复。
- **衔接与过渡**：支持 **无缝衔接（Gapless，同专辑连续曲目优先直连）** 与最长 **12 秒交叉渐变**，跨曲时封面同步混合过渡。
- **播控与均衡器**：**WSOLA 变速不变调（0.5×–2.0×）**、**A-B 区间复读（支持起止点毫秒级微调）**、**睡眠定时**（按分钟或播完本曲停止），以及带内置预设和自定义配置档的 **10 段图形均衡器（±12 dB）**。
- **动态音量标准化**：基于 **BS.1770 LUFS** 的响度分析与实时增益控制，支持单曲 / 专辑模式、目标响度调节与防削波保护。
- **队列与系统表面**：下一首播放、加入队列、队列去重、拖动排序、移除、随机与循环（支持跨重启队列与进度恢复）；全屏页显示无损 / Hi-Res / DSD 音质徽章与参数弹窗、可选音量条与系统音频输出切换；提供 **3 种媒体通知样式**（默认 / 大封面 / 扩展控制）、**桌面小组件** 与 **快捷设置磁贴**。

### 歌词与对齐

- **歌词表现**：支持 **逐字（yrc）卡拉 OK**、翻译、罗马音注音、对唱按演唱者分边显示、间奏三点律动、点击行立即跳转、手动搜索替换歌词、单曲偏移校准与 **LRC 导出**。
- **多源聚合与单曲记忆**：支持 **网易云 → AMLL → LRCLIB → 酷狗** 四源，优先级、开关与「逐字回退源」均可配置；开启单曲歌词记忆后可为每首歌独立保存歌词源与对齐开关。
- **端侧 ONNX 强制对齐**：缺少逐字时间轴时，可通过精简算子集 **ONNX Runtime** 在本地自动对齐；中文 / 英文 / 日文模型及日文 OpenJTalk 词典按需后台下载、可取消或删除。
- **动效与系统歌词**：可调歌词弹簧强度、手动滑动后回焦延迟与自动进入全屏；支持 **Lyricon 提供端**、**媒体通知歌词（歌词优先 / 歌名优先）**、**独立歌词通知** 与 **HyperOS 超级岛**。

### 界面、分析与扩展

- **主题与液态玻璃**：跟随系统 / 浅色 / 深色 / **AMOLED 纯黑** 主题、5 种强调色、两套图标风格（Material / Apple 风格）、两套控件风格（磨砂 / 可调模糊度的 **Liquid Glass 液态玻璃**）、减少透明度与屏幕常亮开关。
- **五种「正在播放」主题**：标准、自适应取色、**苹果式封面流动（Kawarp 管线，9 项参数可调）**、扭曲像素，以及 **GL 动态背景**（**网格渐变** 8 预设随机池 + **Titanium 流沙** 颗粒/闪烁/金属感/波峰/抖动等全参数可调），并支持独立开关「自适应字体颜色」与 3 档歌词字号。
- **连续形变交互**：迷你播放条到全屏播放器的连续形变过渡（贴合设备真实屏幕圆角、底栏同步退让/回升、动态背景提前就绪）、底部玻璃透镜滑动与粘性拖拽物理。
- **本地数据分析（Insight）**：完全在本地统计并绘图——曲库容量与增长趋势、音质/流派分布、听歌时长与完播/跳过率、连续天数与单次平均时长、每日热力图、足迹时间线与年度报告。
- **12 种界面语言**：简体中文、英语、日语、韩语、德语、西班牙语、法语、意大利语、葡萄牙语、俄语、泰语、越南语。
- **趣味选项与隐藏彩蛋**：设置内提供多组可独立开关的趣味视听效果，应用内还藏有彩蛋模式，留待自行发现。

### 桌面同步、备份与存储

- **电脑音乐库同步**：支持局域网、组网或公网 HTTPS 配对（6 位配对码 + 证书指纹校验），支持多地址切换、远程流式播放与可调上限的本地预取缓存。
- **全量 ZIP 备份与恢复**：一键导出/恢复设置偏好、本地与智能歌单、收藏、本地听歌统计、歌词偏移、单曲歌词配置与自定义封面（自动排除账号 Cookie、同步凭证与缓存），并内置路径安全与体积校验。
- **存储管理与更新**：清晰展示设备空间、已下载歌曲及四类缓存（流缓存、预取缓存、封面缓存、Hi-Res 封面）占用，支持逐项调整上限与单项/一键清理；支持通过 GitHub Releases 应用内检查更新。

## 下载

从 [Releases](https://github.com/MisakaMkotoo/Trove/releases) 下载 APK。当前版本：**1.2.0-beta**，
APK 按 ABI 拆分（`arm64-v8a` 优先，另有 `armeabi-v7a` 与 `x86_64`）。

系统要求：**Android 12（API 31）及以上**（`targetSdk = 36`）。首次安装需要在系统设置里允许来自该来源的安装。

## 技术栈

- Kotlin Multiplatform：`androidApp` + `sharedLogic`，Compose Multiplatform 1.11.1 / Material3。
- 原生播放内核：FFmpeg 8.1.2 + AAudio（JNI），OpenSSL 3.0.x。
- ONNX Runtime 1.32.0（可按需裁剪算子）用于歌词强制对齐。
- Media3 媒体会话、Haze 模糊、Vico 图表、DataStore 持久化。
- 工具链：Kotlin 2.4.10、AGP 9.3.1、Gradle 9.5.0、JDK 21、NDK 30.0.16248370。

## 构建

环境要求：

- JDK 21
- Android SDK 37（`compileSdk = 37`，`targetSdk = 36`，`minSdk = 31`）
- Android NDK 30.0.16248370
- 使用仓库自带的 Gradle wrapper

FFmpeg 与 ONNX Runtime 的预编译 `.so` 不随仓库分发（`androidApp/src/main/jniLibs/` 已在 `.gitignore` 中）。首次构建前先生成：

```bash
# 全部 ABI 的 FFmpeg
bash scripts/build-ffmpeg-android.sh all

# 精简算子集的 ONNX Runtime（歌词对齐用）
bash scripts/build-onnxruntime-android.sh
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

未配置签名环境变量时，release 包会退回 debug 签名，仅适合本地测试。

## 项目结构

```text
androidApp/     Android 应用（Compose UI、播放服务、网易云、同步）
sharedLogic/    共享的播放队列与曲库模型
scripts/        FFmpeg / OpenSSL / ONNX Runtime 构建脚本与工具脚本
tools/          字体子集、罗马化数据
licenses/       第三方许可证全文
brand/          应用图标源文件
fastlane/       应用商店元数据
```

## 上游与致谢

Trove 最早由 **Tune**（作者 [exodi_dio](https://github.com/Exodi-dio)，GPL-3.0）这个本地播放器起步，
如今已重做为网易云音乐第三方客户端；播放器、界面、网易云集成与同步功能均做了大量重写，并非 Tune 的官方分支。

- 界面设计语言源自 [Airmedy](https://github.com/misa198/airmedy)（GPL-3.0）。
- 部分艺人页交互参考 [BitChord](https://github.com/kushagrasinghx/BitChord)（GPL-3.0）。
- 歌词强制对齐管线与 ONNX 模型来源参考 [MeloX-Android](https://github.com/lladlam/MeloX-Android)（GPL-3.0），SF Symbols 图标映射同样取自该项目。
- 网易云接口、歌词来源择优与请求指纹等实现大量参考 [MeiloX](https://github.com/MeiloX/MeiloX)（GPL-3.0），未直接复用其代码。
- 玻璃动效参考 Glasmio（NEORUAA）与 AndroidLiquidGlass（Kyant0）；封面流动背景基于 Kawarp（Better Lyrics）管线；NCM 解码参考 ncmdump。
- 第三方组件与来源的完整清单见 [NOTICE](NOTICE)。

## 许可证

本项目使用 **GNU General Public License v3.0（GPLv3）**，详见 [LICENSE](LICENSE)。
作为 GPLv3 衍生作品，分发修改版时需保留版权声明与许可证，并提供对应源码。
