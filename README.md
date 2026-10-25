# RingCtl

把小米 17 Ultra 徕卡版的物理变焦环，当作通用旋钮使用：滚动列表、左右翻页、切换页面。

## 功能

- **三种滚动模式**：表冠模式（平滑跟随）、滚轮模式（一格一格）、翻页模式（一次一屏）
- **双向滑动**：竖直上下滚动 / 水平左右翻页
- **单次冷却**：触发一次后指定毫秒内不再响应
- **转动震动**：每触发一次轻震反馈
- **快捷暂停**：临时暂停 1 分钟 / 10 分钟

## 技术原理

```
mt350x OPTICALTRACKING 传感器 (SensorManager, 无需权限)
    → RingForegroundService 计算方向与位移
    → RingAccessibilityService.dispatchGesture() 合成触摸手势
```

- 传感器数据：`xiaomi.sensor.optical_tracking`（type 33171136），普通 App 可直接订阅
- 手势执行：无障碍服务，需要用户手动开启一次
- **不需要 root**

## 编译

推送到 `main` 分支会自动触发 GitHub Actions 编译，产物在 Actions 页面的 Artifacts 中下载。

本地编译：

```bash
./gradlew assembleDebug
```

## 权限说明

| 权限 | 用途 | 是否需要手动开启 |
|---|---|---|
| 无障碍服务 | 执行滑动手势 | 是，一次即可 |
| 通知权限 | 前台服务常驻通知 | 是 |
| 电池优化白名单 | 防止后台被杀 | 建议开启 |

## 已知限制

- 仅适配小米 17 Ultra（nezha / 2512BPNDAC）
- HyperOS 在 APK 更新后可能自动撤销无障碍授权，需要重新开启
- 无法发送真正的鼠标滚轮事件（`AXIS_VSCROLL` 需系统级权限，且 HyperOS 列表已不响应）
