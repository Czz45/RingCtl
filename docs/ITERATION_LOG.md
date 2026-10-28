# 开发迭代记录（0.6.0 → 0.7.1）

> 本文由一次连续的开发对话整理而成，记录 2026-10-28 下午到晚上这一轮迭代：
> 从「方向盘不响应」一路改到「手势互斥」。包含每个问题的根因、当时试错的方案、
> 以及最后落地的做法，供以后回看。

---

## 一、版本时间线

| 版本 | 主要内容 | 结果 |
|---|---|---|
| 0.5.9 | 试图用「预览开关 + 前台包名放行」修 App 内方向盘不响应 | ❌ 仍不动 |
| 0.6.0 | 方向盘页面自己订阅传感器，服务让路 | ✅ 能动 |
| 0.6.1 | 角度模型拆分：满舵角度 / 角度上限；补全方向盘参数 | ✅ |
| 0.6.2 | 盘面按真实角度转 + 推力弧；灵敏度改「满舵圈数」+ 一键校准 | ❌ 编译失败（`@Composable` 重复） |
| 0.6.3 | 修掉重复注解 | ✅ |
| 0.6.4 | 每圈计数默认 4500；自动归中改为「中间附近缓慢吸回」 | ✅ |
| 0.6.5 | 插值改时间常数模型；输出频率 125/250/500/1000Hz | ✅ |
| 0.6.6 | 修归中抽搐；加归中震动、触边震动 | ✅ |
| 0.6.7 | 过中位震动；内置 APK 下载器 | ✅ |
| 0.6.8 | 来回扭转手势（启动应用 / 应用内功能） | ✅ |
| 0.6.9 | 手势动作扩到播放控制（媒体键） | ✅ |
| 0.7.0 | 手势与全局开关互斥；触发时清滚动积压 | ✅ |
| 0.7.1 | 修「粘住」：归中吃掉慢转 + 插值线程漏目标 | ✅ |

---

## 二、核心问题与根因

### 1. App 内方向盘完全不响应

**现象**：方向盘页面上转环，盘纹丝不动；但在别的游戏里正常。

**根因**：`RingForegroundService.isActiveForCurrentApp()` 里有一条防自激判断：

```java
// 自己这个 App 不响应，避免自己转自己
if (pkg != null && pkg.equals(getPackageName())) {
    return false;
}
```

变焦环数据只在这个服务的 `onSensorChanged` 里产生。用户在自己页面转环时，
前台包名就是 `com.breeze.ringctl`，事件在入口被丢掉，`VirtualPad.turn()` 从没被调用。

**0.5.9 的错误尝试**：加「预览开关」，在判断里放行自己。
但放行依赖 `ForegroundApp.get()` 能正确返回自己：
没给「使用情况访问权限」时返回 null → `isIgnorable(null)` 为真 → 退到无障碍服务的
`foregroundPkg` → 无障碍没开还是 null。任一环断掉，放行就不成立。

**0.6.0 的正解**：页面自己订阅传感器。

```
方向盘页面 registerListener（SENSOR_DELAY_GAME）
  → values[2] 相邻帧求差
  → × 换算 → VirtualPad.turn()
  → libringpad.so → /dev/uinput → ABS_X
```

同时 `padPreview = true` 让服务在 `isActiveForCurrentApp()` 最开头直接 `return false`
（**让路**，不是放行），避免两处同时累加角度。

**必须配生命周期**：`padPreview` 是静态标记，只在 `DisposableEffect` 里开关的话，
按 Home 离开时页面没销毁，标记一直是 true，会吃掉别的 App 的转环事件。
所以 `MainActivity.onPause()` 清掉、`onResume()` 在方向盘页可见时恢复。

**教训**：
- 「自己 App 不响应」这类防自激逻辑会顺带砍掉自测入口
- 不要用前台包名判断「是否在本页面」，权限/无障碍任一缺失就失效
- 页面级功能尽量自己拿数据源，链路越短越不容易断

---

### 2. 转 500°、900° 指针都不到半圈

**根因**：`turn()` 把累计角直接 clamp 在 ±maxDeg：

```java
angle += deltaDeg;
if (angle > maxDeg) angle = maxDeg;
```

转 500 和 900 都停在 540，视觉上又按「满舵 = 135°」压缩，所以看起来完全一样。

**修法**：拆成两个量。

| 参数 | 含义 |
|---|---|
| `padTurnsToFull` | 摇杆推到 100% 需要环转几圈（手感） |
| `padLimitTurns` | 盘最多转到几圈（行程上限） |

角度累加到上限才停，摇杆值在换算时单独 clamp。超程只是不再加推力，角度照常累计。

**盘面显示**：`visualDeg = angleDeg`（不再压缩），另外加一圈推力进度弧
（12 点向两侧 ±135°）——盘转多圈后三辐条图形会自我重合，弧是唯一稳定读数。

---

### 3. 「灵敏度」和「满舵角度」语义重叠

**根因**：推力公式是 `count × gain ÷ maxDeg`，两个旋钮调的是同一个比值。
gain 调 0.5 和 maxDeg 调 270 效果完全一样。

**修法**：拆成「校准量」和「手感受量」。

```
每圈计数 countsPerTurn   环转一圈 = 多少传感器计数（校准，实测约 4500）
满舵圈数 turnsToFull     环转几圈 = 摇杆 100%（手感，默认 1.25）
推力 = 计数 ÷ (每圈计数 × 满舵圈数)
```

新增「一键校准」：点校准 → 慢慢转一整圈 → 停手 1.2 秒自动记录计数差。
旧配置自动迁移，且 0.6.2/0.6.3 迁移出来的 1440 会被纠正为 4500（实测值）。

---

### 4. 归中时来回抽搐

**根因**：两个线程抢同一个角度值。

```
PadCenter 线程：每 30ms 把目标角度减 2.7°
PadGlide  线程：每 4ms 把显示值往目标追
```

节奏错开时，归中把目标减到 0 而显示值还在半路，下一拍归中又动，
观感就是 0 附近来回跳。

**修法**：归中并入插值循环，按 `dt` 逐帧衰减：

```java
target -= speed * dt / 1000     // 250Hz 时每帧 0.36°，没有阶梯
```

`PadCenter` 线程降级为「看门狗」：到点了把插值线程叫起来，不碰角度。

---

### 5. 方向盘「粘住」——转不动，过一会才动（0.7.1）

两个独立原因：

**(a) 归中吃掉慢转**

```java
// 原来：只有转够 1° 才重置归中计时
if (Math.abs(deltaDeg) >= 1f) { lastTurnMs = now; }
```

调用方已经用死区过滤过噪声，进来都是有效输入。慢转每步 < 1°，时间戳不更新，
归中判定「用户没动」，立刻把刚转的角度吸回去 → 转一点被吃一点。

改成任何一次 `turn()` 都重置计时。

**(b) 插值线程漏目标**

`PadGlide` 判断追平准备 break 的瞬间，`turn()` 改了目标并调 `startGlide`，
此时 `glideRunning` 仍是 true → 新目标被丢弃 → 线程退出 → 没人追。
现在 `finally` 里补一次检查（差值 ≥0.2° 或归中仍激活就重新起线程）。

---

### 6. 插值频率：为什么不能只提高采样率

传感器硬件上限就是 50Hz（`samplingPeriod = 20000us`）。
请求 1000Hz 只会被驱动夹回 20000us，一次都不会多。

原来的插值是「每 8ms 走剩余差值的 35%」，**和频率强耦合**：
同一个 35% 拿到 1ms 周期上，速度就快 8 倍，1000Hz 会直接冲过头震荡。

改成与频率无关的指数逼近：

```
tau   = 8 / -ln(1 - 跟随速度)
alpha = 1 - exp(-dt / tau)
shown += (target - shown) * alpha
```

`dt` 用真实经过时间算。125Hz 和 1000Hz 走同一条曲线，只是采样更密。

配套：
- 节拍按 `nanoTime` 对齐，最后不足 1ms 用自旋补
- 落后时重置节拍，防止越追越慢
- 传感器回调挪到独立 HandlerThread（页面和服务都改了），
  否则页面重组 + Canvas 绘制会推迟回调，表现为「转着转着顿一下」
- 热路径去掉 `ensureCreated`，只判断 ready 后直接写

---

### 7. 来回扭转手势

**上游调研**：HyperZoomRing 和 OmniRing 都**没有**这种手势。
它们的手势只有四档：

```kotlin
enum class GestureType { CW_SLOW, CW_FAST, CCW_SLOW, CCW_FAST }
```

判定是「400ms 窗口内事件数 > 阈值 = fast」。另有 `reverseMinSamples = 2`
防单帧反向尖峰，但那是防抖，不是触发条件。

**自研 TwistDetector**：把转动拆成半程，方向翻转时结算；
半程幅度达标才算有效翻转；800ms 内累计 2 次有效翻转 = 扭了一个来回。

**关于「查找应用内功能」**：Android 没有公开接口能枚举第三方 App 的付款码页面。

| 方案 | 可行性 |
|---|---|
| App Shortcuts | `ShortcutManager` 只能读自己，读别家要 `ACCESS_SHORTCUTS`（系统级） |
| Deep link / scheme | ✅ 主用。应用自己注册的，能直接用 |
| 硬编码 Activity | 易随版本失效 |

所以做成「预设 + 自定义」：内置微信/支付宝/QQ 常见 scheme，失效可自己填。

---

### 8. 手势被滚动打断 / 与全局开关互斥（0.7.0）

**现象**：手势触发打开应用后，刚弹出的界面被滑走。

**根因**：手势触发后滚动逻辑还在继续，`pendingPx` 里的积压继续发 swipe。

**修法**：
```java
pendingPx = 0f;
wheelAccum = 0f;
cooldownUntilMs = now + 800;   // 滚动停手 800ms
```

**互斥**：手势与全局开关不能同时开，在 `Cfg` 里双向保证（不只靠 UI）：

```java
setEnabled(true)       → twist_on = false
setTwistEnabled(true)  → enabled = false
```

手势模式下转动只服务手势，不再走滚动/方向盘；
`isActiveForCurrentApp()` 在手势开启时放行（否则全局关了手势也收不到数据）。

---

## 三、工程侧踩坑

### 1. `@Composable` 重复导致编译失败

改 `SteeringWheelView` 参数时，新文本里带了一个 `@Composable`，
而锚点从 `fun SteeringWheelView(` 开始，结果变成两个连续注解：

```
e: MainActivity.kt:1531:1 This annotation is not repeatable.
```

**教训**：用 Edit 改函数签名时，注意锚点是否已包含注解行。

### 2. 只推 main 不会出 Release

工作流里写死了：

```yaml
- name: Create Release
  if: startsWith(github.ref, 'refs/tags/v')
```

只推 main 只有 Actions Artifact（要登录下载），Releases 页面什么都没有。
**发版必须推 main + 推 tag。**

### 3. github.com:443 连不上时用 API 提交

现象：`git push` 报 `Failed to connect to github.com port 443` 或
`GnuTLS recv error (-110)`，但 `api.github.com` 正常。

兜底方案是走 Git Data API：blob → tree → commit → 更新 ref → 建 tag。
脚本要点：

```python
base_sha  = GET /git/ref/heads/main
base_tree = GET /git/commits/{base_sha}.tree.sha
blob      = POST /git/blobs {content: base64, encoding: "base64"}
tree      = POST /git/trees {base_tree, tree: [{path, mode:"100644", type:"blob", sha}]}
commit    = POST /git/commits {message, tree, parents:[base_sha]}
PATCH /git/refs/heads/main {sha, force}
POST  /git/refs {ref:"refs/tags/vX.Y.Z", sha}
```

注意：通过 API 创建的 tag **会**触发 `push: tags` 工作流（实测有触发）。

### 4. 完整对话里的技术判断

- 「提高采样率到 1000Hz」对传感器无效（硬件 50Hz），但**提高插值输出频率**有效
- 归中不该用独立线程改目标值，应该和插值同频
- 手势判定阈值要按「度数」而不是「原始计数」思考，否则换机就失效

---

## 四、当前参数总览

### 方向盘

| 参数 | 默认 | 作用 |
|---|---|---|
| 满舵圈数 | 1.25 | 环转几圈到 100% |
| 角度上限 | 3 圈 | 盘最多转多少 |
| 每圈计数 | 4500 | 校准值，可一键校准 |
| 死区 | 2 | 单帧抖动过滤 |
| 摇杆曲线 | 线性 | 柔和/线性/激进 |
| 跟随速度 | 35% | 时间常数 19ms 左右 |
| 输出频率 | 250Hz | 125/250/500/1000 |
| 方向反转 | 关 | 独立于滚动模式 |
| 自动归中 | 关 | 范围 ±90°，90°/s，延时 700ms |
| 震动 | 开 | 触边 / 归中到位 / 过中位 |

### 手势

| 参数 | 默认 | 作用 |
|---|---|---|
| 来回扭转 | 关 | 与全局开关互斥 |
| 扭动幅度 | 150 计数 | 每个半程至少转多少（约 12°） |
| 需要翻转 | 2 次 | 2 = 一个来回 |
| 动作 | — | 启动应用 / 应用内功能 / 播放控制 |

---

## 五、仍待处理

见 [TODO.md](./TODO.md)：

1. 搜索结果点不进设置（整行被绑成开关）
2. App 详情页缺方向盘参数（且服务需改读 `AppCfg` 覆盖值）

新增待办：

3. 视频进度目前用媒体键（`KEYCODE_MEDIA_FAST_FORWARD`），跳跃秒数由播放器决定。
   要精确 seek 需接 `MediaSession` + 通知使用权。
4. 手势表目前只有一个手势（来回扭转），结构上已支持扩展，
   后续可加「快速转到底」「慢转三圈」这类组合。
