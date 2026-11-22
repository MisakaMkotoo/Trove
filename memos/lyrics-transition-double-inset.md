# 2026-11-22 · 过渡结束后下一句焦点偏移再瞬移回位

## 现象

切换“是否开启翻译”后，焦点附近的歌词（尤其下一句）位置看起来偏了；过一段时间（约 2.4s）又瞬间跳回正常焦点位置。

## 根因

逐行过渡的屏幕 Y 公式重复计入了顶部焦点空区 `topInsetPx`：

- 正确：屏幕 Y = `prefixTop(i) - scroll`，其中 `prefixTop` 已包含 `topInsetPx`；
- 错误实现：`topInsetPx + prefixTop(i) - scroll`（以及过渡终点 `finalScreenY` 也多加了一次）。

结果：过渡期间整列比正确位置低约一个焦点高度；2.4s 收尾 `finishTransition()` 把锚点按正确公式复位时，行位置整体瞬移回去。切换翻译会改变行高，让偏移更明显。

## 修复

- `rowScreenYAt` 与 `finalScreenY` 去掉多余的 `topInsetPx`；
- 布局放置仍使用 `topInset + rawTop - scroll`（等价于 `prefixTop - scroll`），保持首行焦点空区行为。

## 状态

已修（2026-11-22）。
