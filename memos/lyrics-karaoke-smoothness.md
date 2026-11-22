# 2026-11-23 · 逐字填充断层 / 上下动掉帧

## 现象

1. 逐字扫过的软边有明显“断层”（一格一格的 alpha 台阶）。
2. 字在上下抬动时观感帧率偏低、不连贯。

## 根因

1. 软边是 8 段离散 alpha 条带近似，按 0.5×词高（约 51px @3x）均分，每段约 6px 一个硬跳变。
2. 歌词时钟 `rememberLyricClock` 的 LaunchedEffect 以 `positionMs` 为 key，播放头每 200ms 更新一次 → 每秒重启 5 次帧循环，周期性漏帧。
3. 抬升用 `drawText(topLeft = y - lift)` 每帧重栅格化字形，亚像素移动下字体渲染不够连续。

## 修复

- 时钟改为常驻单循环：200ms tick 只更新基准时间，不重启；暂停时冻结。
- 软边改为真正的渐变遮罩：当前词画入 saveLayer，用水平 LinearGradient 以 DST_IN 乘到图层上（1→0，以扫过位置为中心），跨词光晕单独用低 alpha 画下一词左侧一小段。
- 字形预生成白色位图（`MultiParagraph.paint` 到 ImageBitmap），逐帧只做位图平移/alpha 合成，配合双线性过滤，消除逐帧重栅格化。

## 状态

已修（2026-11-23）。
