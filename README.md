# RingCtl

把小米 17 Ultra 徕卡版的物理变焦环，当作通用旋钮使用：滚动列表、左右翻页、切换页面。

**当前版本：v0.1 (Beta)**

## 作者

- 酷安：[@橙子味的风](https://www.coolapk.com/u/26711585)
- 仓库：[Czz45/RingCtl](https://github.com/Czz45/RingCtl)

## 功能

- **四种滚动模式**：表冠模式（平滑跟随）、滚轮模式（一格一格）、翻页模式（一次一屏）、连续拖拽（手指按住持续跟随）
- **双向滑动**：竖直上下滚动 / 水平左右翻页
- **平滑窗口**：转动位移先累积再一次性滑出，消除一顿一顿
- **单次冷却**：触发一次后指定毫秒内不再响应
- **转动震动**：强度和时长可调
- **快捷暂停**：临时暂停 1 分钟 / 10 分钟
- **Root 模式**：一键开关无障碍服务，免去手动跳转系统设置
- **内置更新检查**：自动比对 GitHub Releases 版本

## 技术原理

```
mt350x OPTICALTRACKING 传感器（SensorManager，无需权限）
    → RingForegroundService 计算方向与位移
    → RingAccessibilityService 合成触摸手势
```

- 传感器数据：`xiaomi.sensor.optical_tracking`（type 33171136），普通 App 可直接订阅，无需权限
- 手势执行：无障碍服务，需手动开启一次（有 root 可一键开启）
- **不需要 root 即可使用**，root 只是省去开权限的步骤

## 下载安装

到 [Releases](https://github.com/Czz45/RingCtl/releases) 下载最新 APK。

首次使用需要：

1. 开启无障碍服务（App 内点「一键开启」或跳系统设置）
2. 启动控制服务
3. 建议把电池策略设为「无限制」

## 编译

推送到 `main` 分支会自动触发 GitHub Actions 编译，产物在 Actions 页面的 Artifacts 中下载。

本地编译：

```bash
./gradlew assembleDebug
```

## 权限说明

| 权限 | 用途 | 需要手动开启 |
|---|---|---|
| 无障碍服务 | 执行滑动手势 | 是（一次即可，root 可自动） |
| 通知权限 | 前台服务常驻通知 | 是 |
| 震动 | 转动反馈 | 否 |
| 网络 | 检查更新 | 否 |
| 电池优化白名单 | 防止后台被杀 | 建议开启 |

## 已知限制

- 仅适配小米 17 Ultra（nezha / 2512BPNDAC）
- HyperOS 在 APK 更新后可能自动撤销无障碍授权，需要重新开启
- 无法发送真正的鼠标滚轮事件（`AXIS_VSCROLL` 需系统级权限，且 HyperOS 列表已不响应该事件）
- 连续拖拽模式受限于 `dispatchGesture` 的调用频率，无法做到完全无空隙

## 致谢

- [NextAlone/HyperZoomRing](https://github.com/NextAlone/HyperZoomRing) — 变焦环传感器方案的先行者
- [TvTjiesen/OmniRing](https://github.com/TvTjiesen/OmniRing) — 表冠式交互的参考实现

## 许可

仅限个人学习使用。
