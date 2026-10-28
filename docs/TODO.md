# RingCtl 待改清单

> 用户提出、确认要改但排到下次一起做的项。动手前先看这里。

---

## 1. 搜索结果点不进设置（必须先开开关再退出）

**位置**：`MainActivity.kt` → `AddAppScreen` 里 `AppRow(...)` 的 `onClick`

```kotlin
onClick = { AppCfg.setAppState(ctx, app.pkg, if (st == 1) 0 else 1); refreshKey++ },
```

**现象**：点列表行等于切换开关，整行都是开关行为，没法进详情页。
用户要先点一次（把开关打开）→ 返回 → 去「已配置应用」列表 → 再点进去设置。

**目标**：点行进 `AppDetailScreen` 看/改参数；只有右侧 `Switch` 切开关。

**改法**：
1. `AddAppScreen` 增加参数 `onOpenDetail: (String) -> Unit`
2. `AppRow` 的 `onClick` 传 `{ onOpenDetail(app.pkg) }`
3. `AppTab` 里 `AppTabHost` 已有 `detailPkg` 状态，把它接上（`2 -> AppDetailScreen(activity, detailPkg...)`）

---

## 2. 应用详情页没有方向盘参数

**位置**：`MainActivity.kt` → `AppDetailScreen`

**现象**：详情页只有「滚动模式」二选一（无障碍滑动 / 方向盘），
选中方向盘后没有任何手柄参数可调，只能退回全局的设置页调，
但那是全局值，不是这个 App 的。

**目标**：选方向盘后，下面出现方向盘参数分组，支持「跟随全局 / 自定义」，
与现有 `OvHeader` 的交互一致。

参数清单（键名沿用全局的，去掉 `pad_` 前缀也可以，保持一致更好）：

| 参数 | 类型 | 全局方法 |
|---|---|---|
| 满舵圈数 | float | `Cfg.padTurnsToFull` |
| 每圈计数 | float | `Cfg.padCountsPerTurn` |
| 死区 | float | `Cfg.padDead` |
| 摇杆曲线 | int | `Cfg.padCurve` |
| 跟随速度 | float | `Cfg.padSmooth` |
| 输出频率 | int | `Cfg.padHz` |
| 方向反转 | bool | `Cfg.padReverse` |
| 自动归中 | bool | `Cfg.padAutoCenter` |

**改法**：
1. `AppCfg` 已有通用覆盖机制（`intOf` / `floatOf` / `boolOf`），直接复用，
   键名用 `pad_turns_full`、`pad_counts_turn`、`pad_dead`、`pad_curve`、
   `pad_smooth`、`pad_hz`、`pad_reverse`、`pad_auto_center`
2. `AppDetailScreen` 增加 `ovPadXxx` 状态 + 一个 `OvHeader("方向盘", ...)` 卡片
3. **关键**：`RingForegroundService` 的 `MODE_WHEEL_PAD` 分支现在全读 `Cfg.*`，
   要改成 `AppCfg.floatOf(this, fgPkg(), "pad_turns_full", Cfg.padTurnsToFull(this))` 这种形式，
   否则详情页改了不生效
4. `VirtualPad.turn()` 里的 `padLimitTurns` / `padCurve` / `padSmooth` 是全局读的，
   `toAxis` 和 `startGlide` 也要吃 App 级覆盖 —— 最省事的做法是
   `VirtualPad` 增加一个 `setScope(Context, pkg)`，内部保存当前作用域，
   读配置时统一走 `padCfg(ctx, key, global)` 包装

**注意**：方向盘预览页（`SteeringWheelScreen`）始终用全局值，
App 覆盖只影响该 App 在前台时的实际输出。

---

## 3. 备忘：已修但容易回归的点

- `isActiveForCurrentApp()` 里有一条「自己这个 App 不响应」的判断，
  页面预览靠 `padPreview` 让服务让路（`return false`），别把顺序写反
- `padPreview` 必须配 `MainActivity.onPause/onResume`，否则按 Home 后
  会吃掉别的 App 的转环事件
- 发版必须 **推 main + 推 tag**，只推 main 不会出 Release
- git push 偶发连不上 443，可用 API 打 tag：
  `POST /repos/Czz45/RingCtl/git/refs {"ref":"refs/tags/vX.Y.Z","sha":"<main sha>"}`
