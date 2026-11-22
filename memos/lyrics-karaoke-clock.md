# 2026-11-22 · 逐字歌词扫亮/抬升按 200ms 一档跳

## 现象

逐字歌词上线后，填充不是连续扫过，而是每隔约 200ms 跳一段；正在唱的段也几乎是瞬间抬起，没有段内动画。

## 根因

`rememberLyricClock` 的帧循环每帧写入的是 `positionMs + 单帧增量`：

```
var lastFrame = withFrameNanos {}
while (true) {
    val frame = withFrameNanos {}
    state = positionMs + (frame - lastFrame) / 1_000_000
    lastFrame = frame          // 增量每帧被重置
}
```

结果时钟永远停在「本次 200ms tick + 一帧」，只有 tick 更新时才整档跳变。扫亮与抬升都在读这个时钟，于是退化为 200ms 分档。

## 修复

- 记录本次 tick 的起始帧 `startedAtFrame`，每帧写 `positionMs + (frame - startedAtFrame)`，帧间连续累加。
- 顺带补上 yrc 字时长为 0 时的有效时长：用与下一段的起始间隔作为平均速度依据。

## 状态

已修（2026-11-22）。
