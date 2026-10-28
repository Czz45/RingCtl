# RingCtl 开发记录：虚拟手柄（方向盘）从零到可用

> 本文档记录把「变焦环 → 虚拟游戏手柄摇杆」这条路走通的全过程。
> 包含大量踩坑、失败方案和最终结论，供以后参考，避免重复走弯路。

---

## 一、最终方案概述

```
小米 17 Ultra 徕卡版变焦环
  ↓ mt350x OPTICALTRACKING 传感器（50Hz）
RingCtl App（无障碍服务 + 前台服务）
  ↓ 累计角度 → -32767 ~ 32767
libringpad.so（JNI）
  ↓ ui_DEV_SETUP + UI_ABS_SETUP + write(ABS_X)
/dev/uinput
  ↓ 内核创建虚拟输入设备
系统 InputManagerService
  ↓ 识别为 Xbox 360 手柄
游戏 / Gamepad Tester
```

**关键三个突破点：**

1. **SELinux**：App 域（`untrusted_app_34`）被拒绝写 `/dev/uinput`，需要 `ksud sepolicy patch`
2. **VID/PID**：必须用 Xbox 360 真实 ID（`0x045e / 0x028e`），系统才有对应 keylayout
3. **NDK**：`UI_DEV_SETUP` 要传结构体，纯 Java 的 `Os.ioctlInt` 做不到

---

## 二、走过的弯路（按时间顺序）

### 阶段 1：MotionEvent + SOURCE_JOYSTICK（失败）

**思路**：在 system_server 里 `injectInputEvent` 注入 `MotionEvent`，带上 `SOURCE_JOYSTICK` 标志。

**结果**：Gamepad Tester 里只有方向键亮，摇杆坐标恒为 `(0.00000, 0.00000)`。

**原因**：
```
MotionEvent 本质是「指针/触摸事件」，携带的是 x/y 坐标。
即使标了 SOURCE_JOYSTICK，系统仍按坐标解析：
  x < 0 → 判定为「左」
  x > 0 → 判定为「右」
中间过程全部丢失，不会映射到 AXIS_X 轴值。
```

**结论**：`MotionEvent` 无法表达连续轴值，这条路彻底不通。

---

### 阶段 2：uinput 命令行工具（能创建，但无法常驻）

**思路**：用 root 执行系统自带的 `/system/bin/uinput` 命令，传 JSON 配置。

**格式**（来自 `frameworks/base/cmds/uinput/README.md`）：

```json
{"id":1,"command":"register","name":"设备名","vid":1174,"pid":654,"bus":3,
 "configuration":[
   {"type":"UI_SET_EVBIT","data":["EV_KEY","EV_ABS","EV_SYN"]},
   {"type":"UI_SET_ABSBIT","data":["ABS_X","ABS_Y"]}
 ],"duration":8000}
{"id":2,"command":"inject","events":["EV_ABS","ABS_X",-20000]}
{"id":3,"command":"inject","events":["EV_SYN","SYN_REPORT",0]}
```

**结果**：设备能创建成功，但**进程一退出设备就销毁**。

**尝试过的常驻方案，全部失败**：

| 方案 | 结果 |
|---|---|
| `tail -f file \| uinput` | KernelSU 下管道进程起不来 |
| FIFO + 后台进程 | su 会话结束进程被杀 |
| `nohup ... &` | 同上 |
| `ProcessBuilder` 保持 stdin | 读到 EOF 立刻退出 |

**根本原因**：
```
uinput 从文件/stdin 读，读到 EOF 就退出。
ADB / su 每次命令都是新会话，会话结束子进程被回收。
所以「设备存活 = 进程存活」这个约束无法满足。
```

---

### 阶段 3：native uinput（正确方向，但卡在权限）

**思路**：写 C 代码，在进程内 `open("/dev/uinput")` 并长期持有 fd。

**为什么必须 native**：
```c
ioctl(fd, UI_DEV_SETUP, &usetup);    // 传 92 字节结构体
ioctl(fd, UI_ABS_SETUP, &abs_setup); // 传结构体
```
而 Android 的 `android.system.Os.ioctlInt(fd, cmd, int)` **只能传 int**，传不了结构体。

**编译问题**：
```
本机环境: aarch64
NDK 自带 clang: linux-x86_64 ELF
→ Exec format error
```
所以只能走 GitHub Actions（ubuntu-latest 自带可用 NDK）。

---

## 三、SELinux：最隐蔽的一关

### 症状

```
E RingCtlPad: open /dev/uinput failed: 13 (Permission denied)
```

而设备权限看起来是对的：

```bash
$ ls -lZ /dev/uinput
crw-rw-rw- 1 root system u:object_r:uhid_device:s0 10, 223 /dev/uinput
```

### 排查过程

**第一步：以为改属组就行（错）**

```bash
chown root:system /dev/uinput    # 属组改成 system
```

但 App 是普通用户 `u0_a456`，**根本不在 system 组里**，所以没用。

**第二步：chmod 666（还是不行）**

```bash
chmod 666 /dev/uinput      # crw-rw-rw-
```

文件权限确实变了，但依然打不开。

**第三步：查 dmesg 看到真相**

```bash
$ dmesg | grep avc
type=1400 audit(...): avc: denied { write } for name="uinput"
  scontext=u:r:untrusted_app_34:s0:c131,c256,c512,c768
  tcontext=u:object_r:uhid_device:s0
  tclass=chr_file
  permissive=0
```

**结论**：**DAC 权限（chmod）在 SELinux（MAC）之后才生效**，SELinux 先拒绝了，chmod 白做。

### 关键细节：域名的 `_34` 后缀

第一次我加的是：
```
allow untrusted_app uhid_device chr_file { write }
```
**没生效**，因为 App 实际域是 `untrusted_app_34`（带 Android 版本号后缀）。

最终有效规则（三条全加）：

```
allow untrusted_app uhid_device chr_file { open read write ioctl getattr map }
allow untrusted_app_34 uhid_device chr_file { open read write ioctl getattr map }
allow untrusted_app_all uhid_device chr_file { open read write ioctl getattr map }
```

### 如何应用

```bash
su -c 'ksud sepolicy patch "allow untrusted_app_34 uhid_device chr_file { open read write ioctl getattr map }"'
```

- `ksud` 是 KernelSU 3.3.0 自带的工具
- **内存 patch，重启失效**
- 所以做成了 App 启动时自动执行（`PadDeviceNative.patchSepolicy()`）

---

## 四、VID/PID：决定系统认不认

### 症状

设备能创建，但 Gamepad Tester 里"只有方向，没有数值"。

### 原因

最初用的是虚构 ID：

```c
usetup.id.vendor = 0x1234;
usetup.id.product = 0x5678;
```

**安卓靠 VID/PID 去 `/system/usr/keylayout/` 找映射文件**：

```bash
$ ls /system/usr/keylayout/ | grep -i 045e
Vendor_045e_Product_028e.kl     ← Xbox 360 有线手柄
Vendor_045e_Product_028f.kl
Vendor_045e_Product_02a1.kl
...
```

虚构的 `1234:5678` 找不到对应文件，系统**不知道这是手柄**，轴值也就不做标准化。

### 参考项目

`TomasThrawat/WifiPad` 的 README 明确写了这个技巧：

> registers with that controller's actual vendor/product ID (0x045e / 0x028e) and
> **every stock Android build already ships the matching keylayout file for it**

### 正确的 keylayout 内容

```
# /system/usr/keylayout/Vendor_045e_Product_028e.kl
key 304   BUTTON_A          ← BTN_A
key 305   BUTTON_B
key 307   BUTTON_X
key 308   BUTTON_Y
key 310   BUTTON_L1         ← BTN_TL
key 311   BUTTON_R1         ← BTN_TR
key 317   BUTTON_THUMBL
key 318   BUTTON_THUMBR
key 314   BUTTON_SELECT
key 316   BUTTON_MODE
key 315   BUTTON_START

axis 0x00 X flat 4096       ← ABS_X   左摇杆横
axis 0x01 Y flat 4096       ← ABS_Y   左摇杆纵
axis 0x03 Z flat 4096       ← ABS_RX  右摇杆横
axis 0x04 RZ flat 4096      ← ABS_RY  右摇杆纵
axis 0x02 LTRIGGER          ← ABS_Z   左扳机
axis 0x05 RTRIGGER          ← ABS_RZ  右扳机
axis 0x10 HAT_X             ← ABS_HAT0X 十字键
axis 0x11 HAT_Y             ← ABS_HAT0Y
```

**注意轴编号的对应关系**：keylayout 里的 `Z`（0x03）对应内核的 `ABS_RX`，`RZ`（0x04）对应 `ABS_RY`，容易搞混。

### 修正

```c
usetup.id.bustype = BUS_USB;
usetup.id.vendor  = 0x045e;   /* Microsoft */
usetup.id.product = 0x028e;   /* Xbox 360 Wired Controller */
usetup.id.version = 0x0110;
```

### 验证成功

```bash
$ cat /proc/bus/input/devices | grep -A8 RingCtlPad
I: Bus=0003 Vendor=045e Product=028e Version=0110
N: Name="RingCtlPad"
H: Handlers=event11
B: EV=b
B: KEY=7fdb000000000000 0 0 0 0
B: ABS=3003f              ← 8 个轴全在

$ dumpsys input | grep -A10 RingCtlPad
11: RingCtlPad
  Classes: KEYBOARD | GAMEPAD | JOYSTICK | EXTERNAL
  Identifier: bus=0x0003, vendor=0x045e, product=0x028e
  KeyLayoutFile: /system/usr/keylayout/Vendor_045e_Product_028e.kl   ← 找到了
  AbsState: ABS_X=0, ABS_Y=0, ABS_Z=0, ABS_RX=0, ABS_RY=0, ABS_RZ=0, ABS_HAT0X=0, ABS_HAT0Y=0
  Sources: KEYBOARD | GAMEPAD | JOYSTICK

# 轴范围（系统已归一化）
X:  source=JOYSTICK, min=-1.000, max=1.000, flat=0.125
Y:  source=JOYSTICK, min=-1.000, max=1.000, flat=0.125
```

---

## 五、手感问题：虚位与跳格

### 问题 1：虚位大

```c
abs_setup.absinfo.flat = 4096;   // 4096/32767 = 12.5% 死区
```

`flat` 是死区，原本是给真实手柄的物理抖动留余量。但我们发的是算好的精确值，不需要这么大。

→ **改成 `flat = 0`**

### 问题 2：跳一大格

```
传感器一帧: ±70 ~ 160 单位
gain 默认:  1.0
→ 一帧就转 70~160°
maxDeg 450
→ 三帧打满
```

→ **gain 默认降到 0.25，maxDeg 提到 540**

### 问题 3：根本原因是没有插值

传感器 50Hz（20ms 一帧），每帧一个大跳变，直接映射成摇杆值就是瞬间跳到位。

**做法**：加插值线程

```java
// 每 8ms 走剩余差值的 35%
while (true) {
    float diff = target - shownAngle;
    if (Math.abs(diff) < 0.5f) break;

    float step = diff * 0.35f;
    if (Math.abs(step) < 1f) step = (diff > 0 ? 1f : -1f);
    shownAngle += step;
    emitValue(shownAngle);

    Thread.sleep(8);
}
```

效果：20ms 的硬跳变，被摊成 8ms 一步的平滑移动（≈125Hz 视觉细腻度）。

---

## 六、传感器实测数据

### 硬件参数

```bash
$ dumpsys sensorservice | grep mt350x
0x00000939) mt350x OPTICALTRACKING Sensor Non-wakeup
0x0000093a) mt350x OPTICALTRACKING Sensor Wakeup

samplingPeriod = 20000us = 20ms = 50Hz
```

**50Hz 是硬件上限，改不了。**

### 每帧 Δ 值

通道 2 是「累计角度的原始计数」，不是度：

| 转速 | 每帧 Δ |
|---|---|
| 慢转 | ±70 |
| 正常 | ±100 ~ 120 |
| 快转 | ±160 |

---

## 七、签名问题

### 问题

一直用 `assembleDebug`，而 debug keystore 在 GitHub Actions runner 上**每次构建都是新生成的**，导致每个版本签名都不同 → 只能卸载重装 → 配置全丢。

### 修复

新建固定 keystore：

```
app/ringctl.jks
CN=RingCtl, OU=Breeze, O=Breeze, L=CN, ST=CN, C=CN
SHA-256: 82396ded986a1316cc0bf4f8755e3f4e0392e9c9a7f8e6eefffa0e40e65f93b5
有效期: 36500 天
```

```kotlin
signingConfigs {
    if (ksExists) {
        create("fixed") {
            storeFile = ksFile
            storePassword = "ringctl2026"
            keyAlias = "ringctl"
            keyPassword = "ringctl2026"
        }
    }
}
buildTypes {
    release { signingConfig = ... }
    debug   { signingConfig = ... }   // debug 也用同一套
}
```

**注意**：keystore 直接提交进仓库，适合私有自用仓库；如果要上架或公开，应改用 GitHub Secrets。

---

## 八、构建配置

### 本机无法编译 native

```
本机:  aarch64
NDK:   linux-x86_64 的 clang
→ Exec format error
```

所以 `externalNativeBuild` 做成条件式：

```kotlin
// 传 -PenableNative=true 时才启用（GitHub Actions 用）
if (project.hasProperty("enableNative")) {
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    ndkVersion = "27.0.12077973"
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a") }
    }
}
```

本地编译（验证 Java 逻辑）：
```bash
gradle assembleDebug --no-daemon \
  -Pandroid.aapt2FromMavenOverride=/root/Android/build-tools/35.0.0/aapt2
```

GitHub Actions 编译（含 native）：
```yaml
run: ./gradlew assembleDebug --no-daemon --stacktrace -PenableNative=true
```

### 发版流程

```bash
# 必须同时推 main 和 tag，否则不会生成 Release
git push origin HEAD:main --force
git tag -f vX.Y.Z
git push origin vX.Y.Z --force
```

**只推 main 不会产 Release**，只上传 Artifact（需登录 Actions 页面下载）。

---

## 九、核心代码位置

```
app/src/main/cpp/
├── pad_device.c           # JNI：uinput 设备创建 + 写 ABS_X
└── CMakeLists.txt

app/src/main/java/com/breeze/ringctl/
├── PadDeviceNative.java   # Java 包装层 + SELinux patch + 权限处理
├── VirtualPad.java        # 角度模型 + 插值 + 发送
├── RingForegroundService.java  # 传感器回调 → 角度计算
├── Cfg.java               # 全局配置（mode / padMaxDeg / padGain）
├── AppCfg.java            # 每个 App 的覆盖配置
└── MainActivity.kt        # UI，含 SteeringWheelScreen 方向盘页面
```

### native 关键函数

| 函数 | 作用 |
|---|---|
| `nativeOpenAndInit(name)` | 本进程 open 设备并完成初始化 |
| `nativeMove(x, y)` | 写 ABS_X / ABS_Y + SYN |
| `nativeDestroy()` | UI_DEV_DESTROY + close |
| `nativeIsReady()` | fd 是否有效 |
| `nativeCanOpen()` | 探测能否打开（不创建设备） |

### 模式常量

```java
MODE_SEGMENT  = 5;   // 无障碍滑动（swipe）
MODE_WHEEL_PAD = 6;  // 方向盘（虚拟手柄）
```

---

## 十、给以后的提醒

1. **SELinux 优先于文件权限** — chmod 不行先查 `dmesg | grep avc`
2. **域名的版本号后缀很关键** — `untrusted_app` ≠ `untrusted_app_34`
3. **虚拟手柄必须用真实 VID/PID** — 否则系统找不到 keylayout
4. **uinput 设备生命周期 = 进程生命周期** — 无法跨进程常驻
5. **50Hz 是传感器硬限制** — 丝滑靠插值补，不是提高采样率
6. **发版要同时推 main 和 tag**
7. **签名固定** — 否则用户每次都得卸载重装

---

## 十一、未完成 / 可优化项

- [ ] 插值步长 8ms → 4ms（更细腻）
- [ ] 加速追赶（35% → 50%，更跟手，但可能过冲）
- [ ] 十字键（HAT0X/HAT0Y）实际使用
- [ ] 扳机键（LTRIGGER/RTRIGGER）映射
- [ ] 方向盘页面实时显示手柄轴值（调试用）
- [ ] 横屏分栏布局

---

## 十二、坑：App 内方向盘不响应

### 症状

方向盘独立页面上，转动变焦环，盘纹丝不动，摇杆也没输出。但在别的 App（赛车游戏）里一切正常。

### 根因

服务里有一条防自激判断：

```java
// 自己这个 App 不响应，避免自己转自己
if (pkg != null && pkg.equals(getPackageName())) {
    return false;
}
```

变焦环的数据只在 `RingForegroundService.onSensorChanged` 里产生。
用户在 App 自己页面里转环时，前台包名就是 `com.breeze.ringctl`，
这条判断直接把事件全部丢弃 —— 后面的角度计算、`VirtualPad.turn()` 一步都没走到。
`VirtualPad.getAngle()` 因此恒为 0，页面上的盘自然不动。

v0.5.9 试图用「预览开关 + 前台包名放行」修补，仍然失败。原因是放行依赖
`ForegroundApp.get()` 正确返回自己：
- 没给「使用情况访问权限」时返回 null
- `isIgnorable(null)` 判定为真，退到无障碍服务的 `foregroundPkg`
- 无障碍没开 → 还是 null → 放行条件不成立

一层层依赖下来，任何一个环没通，页面就不动。

### 最终方案：页面自己订阅传感器

```
方向盘页面 registerListener（SENSOR_DELAY_GAME）
  ↓ values[2] 求相邻帧差
  ↓ × gain → VirtualPad.turn()
libringpad.so → /dev/uinput → ABS_X
```

同时 `RingForegroundService.padPreview = true`，让服务在 `isActiveForCurrentApp()`
最开头直接返回 false（**让路**，不是放行），避免两处同时累加角度。

```java
private boolean isActiveForCurrentApp() {
    if (padPreview) {
        return false;   // 页面接管了，服务别插嘴
    }
    ...
}
```

### 必须处理的副作用

`padPreview` 是静态标记，如果只在 `DisposableEffect` 里开关，用户按 Home 键
离开时页面并没有被销毁，标记一直是 true → 在别的 App 里转环会没有反应。

所以 `MainActivity` 里配合生命周期：

```kotlin
override fun onPause()  { RingForegroundService.padPreview = false }
override fun onResume() { if (wheelVisible) RingForegroundService.padPreview = true }
```

### 经验

1. **「自己 App 不响应」这类防自激逻辑，会顺带砍掉自测入口** —— 加功能时先想清楚哪些路径被它挡住
2. **不要用前台包名去做「当前是否在本页面」的判断** —— UsageStats 权限、无障碍状态任何一个缺失都会让判断失效
3. **页面级功能尽量自己拿数据源** —— 绕开服务、绕开前台识别，链路越短越不容易断
4. **静态开关一定要配生命周期** —— 否则按 Home 之后行为就和预期不一致
5. **页面加一行原始 Δ 显示**（`最近 Δ = N`）—— 一眼区分「没数据」和「没输出」，省掉一轮猜
