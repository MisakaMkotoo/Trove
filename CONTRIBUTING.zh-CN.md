# 参与 Trove

Trove 是个人维护的项目，流程尽量简单。

<p align="center"><a href="CONTRIBUTING.md">English</a> · <a href="CONTRIBUTING.zh-CN.md">简体中文</a></p>

## 反馈问题与建议

在 [Issues](https://github.com/MisakaMkotoo/Trove/issues) 里提，一个 issue 只写一件事。

- 播放/同步类问题请附上：设备型号、Android 版本、复现步骤；有条件的话附日志（`adb logcat`）或录屏。
- 网易云相关的在线功能依赖第三方接口，失效属于预期内的可能情况；能稳定复现的话仍欢迎反馈。
- UI 改动的建议请附截图或参考图，方便对齐视觉。

## 代码

- 默认分支是 `main`，不设长期开发分支；fork 后开一个描述性的分支，向 `main` 提 Pull Request。
- 提交前请至少跑通编译：`./gradlew :androidApp:assembleProdDebug`。
- 保持 Kotlin + Jetpack Compose 的现有风格；界面以 Apple Music 的观感为基准，改动画或视觉效果时请附前后对比。
- 新增文案需要同时补齐 `values/`（英文）与 `values-zh/`（简体中文）。
- 不要在提交里包含：`build/`、`.gradle/`、APK、签名文件（keystore）、`local.properties`、`jniLibs/` 与任何密钥/令牌。
- 项目是 GPL-3.0：提交的代码按同一许可证授权。

## 与上游的关系

本项目衍生自 [Exodi-dio/tune](https://github.com/Exodi-dio/tune)，但独立维护，不会自动跟随上游；上游的改动如果想进入本仓库，请单独提 PR 并在描述里说明来源。

## 其他

安装、设备兼容、功能设想等非缺陷类讨论，也放在 Issues 里即可，标题前加 `[讨论]`。
