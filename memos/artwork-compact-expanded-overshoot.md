# 2026-11-22 · 小封面切大封面时的“过冲”效果

## 现象

从歌词视图收起、紧凑小封面切回大封面时，能明显看到封面先长到比目标更大，再缩回目标大小并停住。
用户确认这是真实几何变化（不是阴影错觉），认可观感，要求保留，仅归档分析。

## 代码审计

封面几何相关动画（FullScreenPlayer.kt）：

- `artworkSize`：tween(320, FastOutSlowIn)
- `artworkHorizontalOffset`：tween(320, FastOutSlowIn)
- `artworkCornerRadius`：tween(320, FastOutSlowIn)
- `topBlockHeight`：tween(320, FastOutSlowIn)
- `artworkScale`：`if (Paused && !isPanelOpen) 0.85f else 1f`，**tween(320)**（面板打开时）与 **tween(500)**（面板关闭时）
- 阴影：dropShadow 固定 radius 12dp / offset 4dp，不参与插值

没有任何 spring 会越过目标。因此过冲只可能来自**多个不同目标/不同时长的 tween 叠加**。

## 最可能的根因（暂停态）

暂停时，大封面的稳定尺寸是 **100% 尺寸 × 0.85 缩放**；歌词视图里 `isPanelOpen=true`，缩放是 1.0。

收起歌词时同时发生：

1. 尺寸 60dp → 满尺寸：**320ms** 到 100%；
2. 缩放 1.0 → 0.85：**500ms** 才落定。

于是封面先快速长到 100%（尚未乘上 0.85），随后再花 200ms 缩到 85% 的稳定大小——视觉上就是“长过头再缩回”。
播放态时缩放恒为 1.0，两条曲线同时到 100%，所以不会出现过冲。这也解释了改 35dp 对齐只是改变了横向端点，并不是过冲的成因。

次要可能：收起过程中 `expandedArtworkSize`/`isPanelOpen` 若中途抖动，`animateDpAsState` 会换目标重跑，也会产生类似“先大后小”。

## 验证方式（未做）

在 `isPanelOpen`/`lyricsView` 翻转后的 ~800ms 内，逐帧记录 `artworkSize / artworkScale / size*scale`，
即可确认是否存在“尺寸先到 100%、总视觉尺寸 > 稳定值”的区间。

## 状态

保留（用户认可观感），不修改；如需精确确认可加逐帧日志。
