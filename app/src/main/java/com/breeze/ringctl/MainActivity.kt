package com.breeze.ringctl

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------- 设计系统色板（来自 ui-ux-pro-max: Dark Mode OLED） ----------
private val CPrimary = ComposeColor(0xFF1E293B)
private val CSecondary = ComposeColor(0xFF334155)
private val CAccent = ComposeColor(0xFF22C55E)
private val COnAccent = ComposeColor(0xFF0F172A)
private val CBackground = ComposeColor(0xFF0F172A)
private val CForeground = ComposeColor(0xFFF8FAFC)
private val CCard = ComposeColor(0xFF1B2336)
private val CMuted = ComposeColor(0xFF272F42)
private val CMutedFg = ComposeColor(0xFF94A3B8)
private val CBorder = ComposeColor(0xFF475569)
private val CDestructive = ComposeColor(0xFFEF4444)

private val AppColors = darkColorScheme(
    primary = CAccent,
    onPrimary = COnAccent,
    secondary = CSecondary,
    onSecondary = CForeground,
    background = CBackground,
    onBackground = CForeground,
    surface = CCard,
    onSurface = CForeground,
    surfaceVariant = CMuted,
    onSurfaceVariant = CMutedFg,
    outline = CBorder,
    error = CDestructive
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                Surface(color = CBackground, modifier = Modifier.fillMaxSize()) {
                    RingCtlScreen(this)
                }
            }
        }
    }
}

@Composable
fun RingCtlScreen(activity: ComponentActivity) {
    val ctx = activity

    var accReady by remember { mutableStateOf(false) }
    var serviceRunning by remember { mutableStateOf(false) }
    var batteryOk by remember { mutableStateOf(true) }
    var hasRoot by remember { mutableStateOf(false) }
    var accBusy by remember { mutableStateOf(false) }
    var notified by remember { mutableStateOf(false) }

    var enabled by remember { mutableStateOf(Cfg.enabled(ctx)) }
    var reverse by remember { mutableStateOf(Cfg.reverse(ctx)) }
    var haptic by remember { mutableStateOf(Cfg.haptic(ctx)) }
    var hapticAmp by remember { mutableIntStateOf(Cfg.hapticAmp(ctx)) }
    var hapticMs by remember { mutableIntStateOf(Cfg.hapticMs(ctx)) }
    var mode by remember { mutableIntStateOf(Cfg.mode(ctx)) }
    var axis by remember { mutableIntStateOf(Cfg.axis(ctx)) }
    var sens by remember { mutableFloatStateOf(Cfg.sens(ctx)) }
    var unit by remember { mutableFloatStateOf(Cfg.wheelUnit(ctx)) }
    var step by remember { mutableIntStateOf(Cfg.stepPx(ctx)) }
    var dead by remember { mutableFloatStateOf(Cfg.deadzone(ctx)) }
    var cooldown by remember { mutableIntStateOf(Cfg.cooldownMs(ctx)) }
    var minInt by remember { mutableIntStateOf(Cfg.minIntervalMs(ctx)) }
    var gestDur by remember { mutableIntStateOf(Cfg.gestureDurationMs(ctx)) }
    var maxSwipe by remember { mutableIntStateOf(Cfg.maxSwipePx(ctx)) }
    var pausedLeft by remember { mutableIntStateOf(0) }

    // 轮询状态
    // 只检测一次 root（结果内部缓存）
    LaunchedEffect(Unit) {
        hasRoot = withContext(Dispatchers.IO) { RootHelper.hasRoot() }
    }

    LaunchedEffect(Unit) {
        while (true) {
            accReady = RingAccessibilityService.isReady()
            serviceRunning = RingForegroundService.running
            batteryOk = isBatteryUnrestricted(ctx)
            delay(500)
        }
    }

    // 首次进入弹出引导
    LaunchedEffect(Unit) {
        if (!accReady) {
            notified = true
        }
    }

    val needsSetup = !accReady || !batteryOk

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 48.dp, bottom = 24.dp)
        ) {
            // ---------- 标题 ----------
            Text(
                "变焦环控制",
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = CForeground
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "把物理变焦环当作通用旋钮",
                fontSize = 14.sp,
                color = CMutedFg
            )

            Spacer(Modifier.height(20.dp))

// ---------- Root 模式 ----------
            if (hasRoot) {
                RootCard(
                    accReady = accReady,
                    onEnable = {
                        accBusy = true
                        Thread {
                            RootHelper.forceRebindAccessibility(ctx)
                            Thread.sleep(800)
                            accBusy = false
                        }.start()
                    },
                    onDisable = {
                        accBusy = true
                        Thread {
                            RootHelper.disableAccessibility(ctx)
                            Thread.sleep(500)
                            accBusy = false
                        }.start()
                    },
                    busy = accBusy
                )
                Spacer(Modifier.height(16.dp))
            }

            // ---------- 设置向导 ----------
            if (needsSetup) {
                SetupCard(
                    accReady = accReady,
                    batteryOk = batteryOk,
                    onAccClick = { openAccessibility(ctx) },
                    onBatteryClick = { openBatterySettings(ctx) }
                )
                Spacer(Modifier.height(16.dp))
            }

            // ---------- 控制卡 ----------
            StatusCard(
                accReady = accReady,
                serviceRunning = serviceRunning,
                onToggleService = {
                    if (serviceRunning) {
                        RingForegroundService.stop(ctx)
                    } else {
                        RingForegroundService.start(ctx)
                    }
                }
            )

            Spacer(Modifier.height(16.dp))

            // ---------- 基本设置 ----------
            SectionLabel("基本设置")
            SettingsCard {
                SwitchRow(
                    icon = Icons.Filled.PowerSettingsNew,
                    title = "启用控制",
                    subtitle = "关闭后转动环不会有任何反应",
                    checked = enabled
                ) {
                    enabled = it
                    Cfg.setEnabled(ctx, it)
                }
                Divider(color = CBorder.copy(alpha = 0.3f))
                SwitchRow(
                    icon = Icons.Filled.SwapVert,
                    title = "反转方向",
                    subtitle = "滚动方向与预期相反时打开",
                    checked = reverse
                ) {
                    reverse = it
                    Cfg.setReverse(ctx, it)
                }
            }
            Spacer(Modifier.height(16.dp))

            // ---------- 震动反馈 ----------
            SectionLabel("震动反馈")
            SettingsCard {
                SwitchRow(
                    icon = Icons.Filled.Vibration,
                    title = "转动震动",
                    subtitle = "每触发一次滑动震一下",
                    checked = haptic
                ) {
                    haptic = it
                    Cfg.setHaptic(ctx, it)
                }
                if (haptic) {
                    Divider(color = CBorder.copy(alpha = 0.3f))
                    SliderRow(
                        icon = Icons.Filled.FitnessCenter,
                        title = "震动强度",
                        valueText = "$hapticAmp",
                        value = hapticAmp.toFloat(),
                        range = 10f..255f,
                        onChange = {
                            hapticAmp = it.toInt()
                            Cfg.setHapticAmp(ctx, hapticAmp)
                        }
                    )
                    Divider(color = CBorder.copy(alpha = 0.3f))
                    SliderRow(
                        icon = Icons.Filled.Timelapse,
                        title = "震动时长",
                        valueText = "$hapticMs 毫秒",
                        value = hapticMs.toFloat(),
                        range = 5f..100f,
                        onChange = {
                            hapticMs = it.toInt()
                            Cfg.setHapticMs(ctx, hapticMs)
                        }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // ---------- 模式 ----------
            SectionLabel("滚动模式")
            SettingsCard {
                ModeRow(Icons.Filled.Tune, "表冠模式", "平滑跟随，转得快滚得远", mode == Cfg.MODE_SMOOTH) {
                    mode = Cfg.MODE_SMOOTH
                    Cfg.setMode(ctx, mode)
                }
                Divider(color = CBorder.copy(alpha = 0.3f))
                ModeRow(Icons.Filled.Mouse, "滚轮模式", "一格一格，像鼠标滚轮", mode == Cfg.MODE_WHEEL) {
                    mode = Cfg.MODE_WHEEL
                    Cfg.setMode(ctx, mode)
                }
                Divider(color = CBorder.copy(alpha = 0.3f))
                ModeRow(Icons.Filled.SwapHoriz, "翻页模式", "一次翻一屏", mode == Cfg.MODE_PAGE) {
                    mode = Cfg.MODE_PAGE
                    Cfg.setMode(ctx, mode)
                }
            }

            // ---------- 滑动方向 ----------
            SectionLabel("滑动方向")
            SettingsCard {
                ModeRow(
                    Icons.Filled.SwapVert,
                    "竖直滑动",
                    "上下滚动列表、刷信息流",
                    axis == Cfg.AXIS_VERTICAL
                ) {
                    axis = Cfg.AXIS_VERTICAL
                    Cfg.setAxis(ctx, axis)
                }
                Divider(color = CBorder.copy(alpha = 0.3f))
                ModeRow(
                    Icons.Filled.SwapHoriz,
                    "水平滑动",
                    "左右切换页面、翻卡片",
                    axis == Cfg.AXIS_HORIZONTAL
                ) {
                    axis = Cfg.AXIS_HORIZONTAL
                    Cfg.setAxis(ctx, axis)
                }
            }

            Spacer(Modifier.height(16.dp))

            Spacer(Modifier.height(16.dp))

            // ---------- 参数 ----------
            SectionLabel("参数调节")
            SettingsCard {
                if (mode == Cfg.MODE_SMOOTH) {
                    SliderRow(
                        icon = Icons.Filled.Speed,
                        title = "灵敏度",
                        valueText = String.format("%.1f 倍", sens),
                        value = sens,
                        range = 0.2f..5.0f,
                        onChange = {
                            sens = it
                            Cfg.setSens(ctx, it)
                        }
                    )
                } else {
                    SliderRow(
                        icon = Icons.Filled.Straighten,
                        title = "一格触发阈值",
                        valueText = String.format("%.0f", unit),
                        value = unit,
                        range = 20f..400f,
                        onChange = {
                            unit = it
                            Cfg.setWheelUnit(ctx, it)
                        }
                    )
                    Divider(color = CBorder.copy(alpha = 0.3f))
                    SliderRow(
                        icon = Icons.Filled.SwapVert,
                        title = "每格滑动像素",
                        valueText = "$step",
                        value = step.toFloat(),
                        range = 90f..580f,
                        onChange = {
                            step = it.toInt()
                            Cfg.setStepPx(ctx, step)
                        }
                    )
                }
                Divider(color = CBorder.copy(alpha = 0.3f))
                SliderRow(
                    icon = Icons.Filled.FilterAlt,
                    title = "死区",
                    valueText = String.format("%.0f", dead),
                    value = dead,
                    range = 1f..115f,
                    onChange = {
                        dead = it
                        Cfg.setDeadzone(ctx, it)
                    }
                )
            }

            Spacer(Modifier.height(16.dp))

            // ---------- 触发节奏 ----------
            SectionLabel("触发节奏")
            SettingsCard {
                SliderRow(
                    icon = Icons.Filled.Timer,
                    title = "单次冷却",
                    valueText = if (cooldown == 0) "关闭" else "$cooldown 毫秒",
                    value = cooldown.toFloat(),
                    range = 0f..5000f,
                    onChange = {
                        cooldown = (it.toInt() / 10) * 10
                    }
                )
                Text(
                    "触发一次后，这段时间内的转动全部忽略",
                    fontSize = 11.sp,
                    color = CMutedFg,
                    modifier = Modifier.padding(start = 50.dp, bottom = 10.dp)
                )
                Divider(color = CBorder.copy(alpha = 0.3f))
                SliderRow(
                    icon = Icons.Filled.Schedule,
                    title = "最小间隔",
                    valueText = "$minInt 毫秒",
                    value = minInt.toFloat(),
                    range = 30f..500f,
                    onChange = {
                        minInt = it.toInt()
                        Cfg.setMinIntervalMs(ctx, minInt)
                    }
                )
                Divider(color = CBorder.copy(alpha = 0.3f))
                SliderRow(
                    icon = Icons.Filled.Timelapse,
                    title = "手势时长",
                    valueText = "$gestDur 毫秒",
                    value = gestDur.toFloat(),
                    range = 40f..400f,
                    onChange = {
                        gestDur = it.toInt()
                        Cfg.setGestureDurationMs(ctx, gestDur)
                    }
                )
                Text(
                    "越大滑动越柔和，越小越干脆",
                    fontSize = 11.sp,
                    color = CMutedFg,
                    modifier = Modifier.padding(start = 50.dp, bottom = 10.dp)
                )
                Divider(color = CBorder.copy(alpha = 0.3f))
                SliderRow(
                    icon = Icons.Filled.OpenInFull,
                    title = "单次最大位移",
                    valueText = "$maxSwipe 像素",
                    value = maxSwipe.toFloat(),
                    range = 90f..900f,
                    onChange = {
                        maxSwipe = it.toInt()
                        Cfg.setMaxSwipePx(ctx, maxSwipe)
                    }
                )
            }

            Spacer(Modifier.height(16.dp))

            // ---------- 快捷暂停 ----------
            SectionLabel("快捷暂停")
            SettingsCard {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (pausedLeft > 0)
                            "已暂停，剩余 $pausedLeft 分钟"
                        else "当前未暂停",
                        fontSize = 14.sp,
                        color = if (pausedLeft > 0) ComposeColor(0xFFF59E0B) else CForeground
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallBtn("暂停 1 分钟") {
                            Cfg.pauseFor(ctx, 1)
                            pausedLeft = 1
                        }
                        SmallBtn("暂停 10 分钟") {
                            Cfg.pauseFor(ctx, 10)
                            pausedLeft = 10
                        }
                        SmallBtn("恢复") {
                            Cfg.resumeNow(ctx)
                            pausedLeft = 0
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ---------- 说明 ----------
            Text(
                "使用提示",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = CForeground
            )
            Spacer(Modifier.height(8.dp))
            listOf(
                "先开启无障碍服务，再启动控制服务",
                "建议把本应用的电池策略设为「无限制」，避免后台被杀",
                "方向反了打开「反转方向」即可",
                "一顿一顿的时候调大死区"
            ).forEach {
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text("· ", color = CAccent, fontSize = 13.sp)
                    Text(it, color = CMutedFg, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
        }
    }
}

// ==================== 组件 ====================

@Composable
private fun RootCard(
    accReady: Boolean,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    busy: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CCard),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = CAccent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Root 模式",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = CForeground
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (accReady) CAccent.copy(alpha = 0.15f) else CMuted)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        if (accReady) "已开启" else "未开启",
                        fontSize = 11.sp,
                        color = if (accReady) CAccent else CMutedFg
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "检测到 root 权限，可免去手动跳转系统设置",
                fontSize = 12.sp,
                color = CMutedFg
            )

            Spacer(Modifier.height(14.dp))

            if (busy) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = CAccent
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("正在应用…", fontSize = 14.sp, color = CMutedFg)
                }
            } else {
                Button(
                    onClick = { if (accReady) onDisable() else onEnable() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (accReady) CMuted else CAccent,
                        contentColor = if (accReady) CForeground else COnAccent
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        if (accReady) Icons.Filled.Close else Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (accReady) "关闭无障碍服务" else "一键开启无障碍服务",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupCard(
    accReady: Boolean,
    batteryOk: Boolean,
    onAccClick: () -> Unit,
    onBatteryClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CCard),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = ComposeColor(0xFFF59E0B),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "还需完成设置",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = CForeground
                )
            }
            Spacer(Modifier.height(12.dp))

            if (!accReady) {
                SetupItem(
                    number = "1",
                    title = "开启无障碍服务",
                    desc = "没有它就无法执行滑动操作",
                    action = "去开启",
                    onAction = onAccClick
                )
                Spacer(Modifier.height(10.dp))
            }

            if (!batteryOk) {
                SetupItem(
                    number = if (accReady) "2" else "2",
                    title = "电池策略设为无限制",
                    desc = "否则后台会被系统杀掉，转动失效",
                    action = "去设置",
                    onAction = onBatteryClick
                )
            }
        }
    }
}

@Composable
private fun SetupItem(
    number: String,
    title: String,
    desc: String,
    action: String,
    onAction: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CMuted)
            .clickable(onClick = onAction)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(50))
                .background(CAccent),
            contentAlignment = Alignment.Center
        ) {
            Text(number, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = COnAccent)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = CForeground)
            Text(desc, fontSize = 12.sp, color = CMutedFg)
        }
        Spacer(Modifier.width(8.dp))
        Text(action, fontSize = 13.sp, color = CAccent, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StatusCard(
    accReady: Boolean,
    serviceRunning: Boolean,
    onToggleService: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CCard),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            StatusLine(
                "无障碍服务",
                if (accReady) "已开启" else "未开启",
                accReady
            )
            Spacer(Modifier.height(8.dp))
            StatusLine(
                "控制服务",
                if (serviceRunning) "运行中" else "已停止",
                serviceRunning
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onToggleService,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (serviceRunning) CMuted else CAccent,
                    contentColor = if (serviceRunning) CForeground else COnAccent
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    if (serviceRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (serviceRunning) "停止控制" else "启动控制",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, ok: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = CMutedFg)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (ok) CAccent else CDestructive)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                value,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (ok) CAccent else CMutedFg
            )
        }
    }
}

@Composable
private fun SmallBtn(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(CMuted)
            .clickable(onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 13.sp, color = CForeground)
    }
}


@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = CMutedFg,
        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CCard),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(content = content)
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = CMutedFg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = CForeground)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 12.sp, color = CMutedFg, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = COnAccent,
                checkedTrackColor = CAccent,
                uncheckedThumbColor = CMutedFg,
                uncheckedTrackColor = CMuted
            )
        )
    }
}

@Composable
private fun ModeRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) CAccent else CMutedFg,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 15.sp,
                color = if (selected) CAccent else CForeground,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
            )
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 12.sp, color = CMutedFg, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(8.dp))
        if (selected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "已选中",
                tint = CAccent,
                modifier = Modifier.size(22.dp)
            )
        } else {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(50))
                    .background(CMuted)
            )
        }
    }
}

@Composable
private fun SliderRow(
    icon: ImageVector,
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = CMutedFg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Text(title, fontSize = 15.sp, color = CForeground, modifier = Modifier.weight(1f))
            Text(valueText, fontSize = 14.sp, color = CAccent, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = CAccent,
                activeTrackColor = CAccent,
                inactiveTrackColor = CMuted
            ),
            modifier = Modifier.height(40.dp)
        )
    }
}

// ==================== 工具 ====================

private fun openAccessibility(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
    }
}

/** 打开本应用的电池优化设置页 */
private fun openBatterySettings(ctx: Context) {
    val intents = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData("package://${ctx.packageName}".toUri()),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData("package://${ctx.packageName}".toUri())
    )
    for (i in intents) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: Exception) {
        }
    }
}

/** 是否已加入电池优化白名单 */
private fun isBatteryUnrestricted(ctx: Context): Boolean {
    return try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (_: Exception) {
        true
    }
}

private fun String.toUri(): android.net.Uri = android.net.Uri.parse(this)