# 2026-11-22 · 歌词点击跳转出现一帧上冲回弹

## 现象

在歌词页手动点击某一行歌词跳转时，列表会先向上冲一帧，随后很快回落，看起来像“抖一下”；
不是预期的平滑滑到焦点位。

## 根因

方案 A（屏幕空间锚点）把跟随动画改成“startFollow 设置锚点 + 后台 LaunchedEffect 积分弹簧”后，
`animateToActiveLine` 变成**立即返回**（不再等到动画结束）。而点词流程仍按“等待完成”的旧假设编写：

1. 点击 → `LaunchedEffect(selectedLineIndex)` 调 `animateToActiveLine(selected)` → 弹簧刚启动就返回；
2. 因为 `selectedIndex == activeIndexWhenLineSelected`（此刻 seek 还没落地，activeIndex 未变），
   `selectedLineIndex` 被立刻清空、`isFollowingSelectedLine` 复位；
3. seek 落地前，跟随 effect 因 `selectedLineIndex` 变化重新执行 → `startFollow(旧 active)`，
   锚点行切换、速度归零；
4. seek 落地后 activeIndex 变为新行 → 跟随 effect 再次 `startFollow(新行)`，再次切换锚点、速度归零。

连续两次“换锚点 + 速度归零”叠加，形成一帧上冲后回落的观感。

## 涉及文件

- `androidApp/src/main/kotlin/com/exodidio/tune/ui/navigation/FullScreenPlayerLyricsPanel.kt`
  - `startFollow` / `LaunchedEffect(followRunId)` 弹簧
  - `LaunchedEffect(selectedLineIndex)`、跟随 effect、`animateToActiveLine`
- `androidApp/src/main/kotlin/com/exodidio/tune/ui/navigation/FullScreenPlayer.kt`（点词 onSeek 未设置 pending seek）

## 修复方向（未实施）

- 让 `startFollow` 暴露“本次弹簧完成”信号（runId + CompletableDeferred），
  选中行/跳转的完成逻辑等真正落定后再清状态；
- 或者：跟随 effect 在 `isFollowingSelectedLine` 或选中动画未完成时一律跳过；
- 或：目标行未变时 `startFollow` 保留当前速度，不重置。

## 修复（2026-11-22）

- 第一轮（06f8faad）：`startFollow` 返回 runId 并新增 `followCompletedRun` 完成信号；
  `animateToActiveLine` 等弹簧真正落定后再返回，点词流程据此延后清理选中态。
- 第二轮：发现根因之一是**拖尾附加位移公式错误**——`lag = (focusY − anchorY) × 系数`
  在向下（点击更靠后的行）时符号相反，导致被点行下方所有歌词瞬间上跳（最多 74% 距离）再被弹簧带回。
  该公式没有保存动画起点，无法保证末端归零，已整体移除；所有行随同一锚点弹簧移动。
- 同目标行再次 `startFollow` 不重启弹簧；换锚点时保留屏幕速度，仅拖拽进入浏览时速度归零。
- 跟随 effect 在 `isFollowingSelectedLine` 期间一律跳过，避免与点词动画并发重锚。

## 状态

已修（2026-11-22，两轮：06f8faad + 移除错误拖尾位移）。
