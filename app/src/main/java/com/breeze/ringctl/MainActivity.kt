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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
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

// ==================== 主框架：底部导航 ====================

class MainActivity : ComponentActivity() {

    /** 方向盘页面是否正在显示，供 onResume 恢复预览标记 */
    var wheelVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(colorScheme = AppColors) {
                Surface(color = CBackground, modifier = Modifier.fillMaxSize()) {
                    MainShell(this)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 回到前台时，若仍停在方向盘页面，恢复预览
        if (wheelVisible) {
            RingForegroundService.padPreview = true
        }
    }

    override fun onPause() {
        super.onPause()
        // 离开 App 必须让路，否则在别的应用里转环会被这个页面吃掉
        RingForegroundService.padPreview = false
    }
}

@Composable
fun MainShell(activity: ComponentActivity) {
    var tab by remember { mutableIntStateOf(0) }
    var showAbout by remember { mutableStateOf(false) }
    var showWheel by remember { mutableStateOf(false) }

    // 记录方向盘页面是否显示，配合 Activity 的 onResume/onPause 控制预览标记
    LaunchedEffect(showWheel) {
        (activity as? MainActivity)?.wheelVisible = showWheel
    }

    if (showWheel) {
        SteeringWheelScreen(activity) { showWheel = false }
        return
    }

    if (showAbout) {
        AboutScreen(activity) { showAbout = false }
        return
    }

    val shared = RingStateHolder(activity)

    // App 一打开就拉起前台服务，让通知立刻可用（可在这里直接启停）
    LaunchedEffect(Unit) {
        try {
            if (!RingForegroundService.running) {
                RingForegroundService.start(activity)
            }
        } catch (e: Exception) {
        }
    }

    // HyperOS 在划掉任务时会清掉无障碍授权，这里用 root 自动恢复
    LaunchedEffect(Unit) {
        try {
            val hasRoot = withContext(Dispatchers.IO) { RootHelper.hasRoot() }
            if (hasRoot) {
                // 稍等一下，让系统先把状态落定
                delay(600)
                if (!RingAccessibilityService.isReady()) {
                    withContext(Dispatchers.IO) {
                        RootHelper.forceRebindAccessibility(activity)
                    }
                    delay(400)
                    // 第一次常常不成功，再试一次
                    if (!RingAccessibilityService.isReady()) {
                        withContext(Dispatchers.IO) {
                            RootHelper.forceRebindAccessibility(activity)
                        }
                        delay(400)
                    }
                }
            }
        } catch (e: Exception) {
        }
    }

    // 启动时检查更新，有新版直接弹窗
    var newVersion by remember { mutableStateOf<String?>(null) }
    var newVersionUrl by remember { mutableStateOf(UpdateChecker.URL_RELEASES) }
    var newVersionLog by remember { mutableStateOf("") }
    var showUpdateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        UpdateChecker.check(activity, object : UpdateChecker.Callback {
            override fun onUpdate(latest: String, url: String, log: String) {
                newVersion = latest
                newVersionUrl = url
                newVersionLog = log
                showUpdateDialog = true
            }

            override fun onLatest(cur: String) {}
            override fun onFail(reason: String) {}
        })
    }

    if (showUpdateDialog && newVersion != null) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            containerColor = CCard,
            titleContentColor = CForeground,
            textContentColor = CMutedFg,
            title = { Text("发现新版本", color = CForeground) },
            text = {
                Column {
                    Text(
                        "当前版本 ${UpdateChecker.currentVersion(activity)}",
                        fontSize = 13.sp,
                        color = CMutedFg
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "最新版本 $newVersion",
                        fontSize = 15.sp,
                        color = CAccent,
                        fontWeight = FontWeight.Medium
                    )
                    if (newVersionLog.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Divider(color = CBorder.copy(alpha = 0.3f))
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "更新内容",
                            fontSize = 13.sp,
                            color = CForeground,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(6.dp))
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                newVersionLog,
                                fontSize = 12.sp,
                                color = CMutedFg,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    UpdateChecker.openUrl(activity, newVersionUrl)
                    showUpdateDialog = false
                }) {
                    Text("去更新", color = CAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }) {
                    Text("忽略", color = CMutedFg)
                }
            }
        )
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
        ) {
            when (tab) {
                0 -> ControlTab(activity, shared, { showAbout = true }, { showWheel = true })
                1 -> AppTabHost(activity, shared)
                else -> SettingsTab(activity, shared, { showAbout = true }, { showWheel = true })
            }
        }
        // 底部导航
        BottomBar(
            current = tab,
            onSelect = { tab = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
        )
    }
}

@Composable
private fun BottomBar(current: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(CCard)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        data class TabItem(val icon: ImageVector, val label: String)
        val items = listOf(
            TabItem(Icons.Filled.Tune, "控制"),
            TabItem(Icons.Filled.Apps, "应用"),
            TabItem(Icons.Filled.Settings, "设置")
        )
        items.forEachIndexed { i, it ->
            val sel = current == i
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelect(i) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    it.icon,
                    contentDescription = it.label,
                    tint = if (sel) CAccent else CMutedFg,
                    modifier = Modifier.size(23.dp)
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    it.label,
                    fontSize = 11.sp,
                    color = if (sel) CAccent else CMutedFg,
                    fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal
                )
            }
        }
    }
}

// ==================== 共享状态 ====================

@Stable
class RingStateHolder(val ctx: ComponentActivity) {
    var accReady by mutableStateOf(false)
    var serviceRunning by mutableStateOf(false)
    var batteryOk by mutableStateOf(true)
    var hasRoot by mutableStateOf(false)
    var accBusy by mutableStateOf(false)

    var enabled by mutableStateOf(Cfg.enabled(ctx))
    var reverse by mutableStateOf(Cfg.reverse(ctx))
    var fixedDir by mutableStateOf(Cfg.fixedDir(ctx))
    var fixedSide by mutableIntStateOf(Cfg.fixedDirSide(ctx))
    var dragHold by mutableIntStateOf(Cfg.dragHoldMs(ctx))
    var dragSeg by mutableIntStateOf(Cfg.dragSegMs(ctx))
    var rootScroll by mutableStateOf(Cfg.rootScroll(ctx))
    var rootSwipe by mutableStateOf(Cfg.rootSwipe(ctx))
    var rootRoll by mutableStateOf(Cfg.rootRoll(ctx))
    var rootStep by mutableIntStateOf(Cfg.rootStepPx(ctx))
    var padMaxDeg by mutableIntStateOf(Cfg.padMaxDeg(ctx))
    var padGain by mutableFloatStateOf(Cfg.padGain(ctx))
    var rootMouse by mutableStateOf(Cfg.rootMouse(ctx))
    var haptic by mutableStateOf(Cfg.haptic(ctx))
    var hapticAmp by mutableIntStateOf(Cfg.hapticAmp(ctx))
    var hapticMs by mutableIntStateOf(Cfg.hapticMs(ctx))
    var mode by mutableIntStateOf(Cfg.mode(ctx))
    var axis by mutableIntStateOf(Cfg.axis(ctx))
    var sens by mutableFloatStateOf(Cfg.sens(ctx))
    var smoothWin by mutableIntStateOf(Cfg.smoothWindowMs(ctx))
    var unit by mutableFloatStateOf(Cfg.wheelUnit(ctx))
    var step by mutableIntStateOf(Cfg.stepPx(ctx))
    var dead by mutableFloatStateOf(Cfg.deadzone(ctx))
    var cooldown by mutableIntStateOf(Cfg.cooldownMs(ctx))
    var minInt by mutableIntStateOf(Cfg.minIntervalMs(ctx))
    var gestDur by mutableIntStateOf(Cfg.gestureDurationMs(ctx))
    var maxSwipe by mutableIntStateOf(Cfg.maxSwipePx(ctx))
    var pausedLeft by mutableIntStateOf(0)

    // 轮询服务与权限状态
    @Composable
    fun PollLifecycle() {
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
    }
}

// ==================== Tab 1：控制 ====================

@Composable
fun ControlTab(
    activity: ComponentActivity,
    s: RingStateHolder,
    onShowAbout: () -> Unit,
    onShowWheel: () -> Unit
) {
    val ctx = activity
    s.PollLifecycle()

    val needsSetup = !s.accReady || !s.batteryOk

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 96.dp)
    ) {
        // ---------- 标题栏 ----------
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    "变焦环控制",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CForeground
                )
                Spacer(Modifier.height(4.dp))
                Text("把物理变焦环当作通用旋钮", fontSize = 14.sp, color = CMutedFg)
            }
            IconButton(onClick = onShowAbout, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = "关于",
                    tint = CMutedFg,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        // ---------- Root 模式 ----------
        if (s.hasRoot) {
            RootCard(
                accReady = s.accReady,
                onEnable = {
                    s.accBusy = true
                    Thread {
                        RootHelper.forceRebindAccessibility(ctx)
                        Thread.sleep(800)
                        s.accBusy = false
                    }.start()
                },
                onDisable = {
                    s.accBusy = true
                    Thread {
                        RootHelper.disableAccessibility(ctx)
                        Thread.sleep(500)
                        s.accBusy = false
                    }.start()
                },
                busy = s.accBusy
            )
            Spacer(Modifier.height(16.dp))
        }

        // ---------- 设置向导 ----------
        if (needsSetup) {
            SetupCard(
                accReady = s.accReady,
                batteryOk = s.batteryOk,
                onAccClick = { openAccessibility(ctx) },
                onBatteryClick = { openBatterySettings(ctx) }
            )
            Spacer(Modifier.height(16.dp))
        }

        // ---------- 前台应用识别权限 ----------
        var usageOk by remember { mutableStateOf(ForegroundApp.hasUsagePermission(ctx)) }
        LaunchedEffect(Unit) {
            while (true) {
                usageOk = ForegroundApp.hasUsagePermission(ctx)
                delay(1000)
            }
        }
        if (!usageOk) {
            SettingsCard {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("使用情况访问权限", fontSize = 15.sp, color = CForeground)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "用于准确识别当前是哪个 App，否则可能判断错应用",
                            fontSize = 12.sp,
                            color = CMutedFg
                        )
                    }
                    SmallBtn("去授权") {
                        try {
                            ctx.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS
                                )
                            )
                        } catch (e: Exception) {
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // ---------- 控制卡 ----------
        StatusCard(
            accReady = s.accReady,
            serviceRunning = s.serviceRunning,
            onToggleService = {
                if (s.serviceRunning) {
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
                title = "全局开关",
                subtitle = "关闭后所有未单独设置的 App 都不响应",
                checked = s.enabled
            ) {
                s.enabled = it
                Cfg.setEnabled(ctx, it)
            }
            Divider(color = CBorder.copy(alpha = 0.3f))
            SwitchRow(
                icon = Icons.Filled.SwapVert,
                title = "反转方向",
                subtitle = "滚动方向与预期相反时打开",
                checked = s.reverse
            ) {
                s.reverse = it
                Cfg.setReverse(ctx, it)
            }
            Divider(color = CBorder.copy(alpha = 0.3f))
            SwitchRow(
                icon = Icons.Filled.SwapHoriz,
                title = "固定方向",
                subtitle = "不管往前还是往后转，都只往一个方向滚动",
                checked = s.fixedDir
            ) {
                s.fixedDir = it
                Cfg.setFixedDir(ctx, it)
            }
            // 固定方向打开后，才显示固定往哪边
            if (s.fixedDir) {
                Divider(color = CBorder.copy(alpha = 0.3f))
                ModeRow(
                    Icons.Filled.ArrowUpward,
                    "固定往正方向",
                    "正转反转都按正方向滚",
                    s.fixedSide == Cfg.FIXED_POSITIVE
                ) {
                    s.fixedSide = Cfg.FIXED_POSITIVE
                    Cfg.setFixedDirSide(ctx, s.fixedSide)
                }
                Divider(color = CBorder.copy(alpha = 0.3f))
                ModeRow(
                    Icons.Filled.ArrowDownward,
                    "固定往反方向",
                    "正转反转都按反方向滚，适合和上面的感觉相反时",
                    s.fixedSide == Cfg.FIXED_NEGATIVE
                ) {
                    s.fixedSide = Cfg.FIXED_NEGATIVE
                    Cfg.setFixedDirSide(ctx, s.fixedSide)
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // ---------- 快捷暂停 ----------
        SectionLabel("快捷暂停")
        SettingsCard {
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("暂停响应", fontSize = 15.sp, color = CForeground)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (s.pausedLeft > 0) "剩余 ${s.pausedLeft} 秒" else "暂停期间转环不会有反应",
                        fontSize = 12.sp,
                        color = CMutedFg
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SmallBtn("暂停 1 分钟") {
                    Cfg.pauseFor(ctx, 1)
                    s.pausedLeft = 60
                }
                SmallBtn("暂停 10 分钟") {
                    Cfg.pauseFor(ctx, 10)
                    s.pausedLeft = 600
                }
                SmallBtn("恢复") {
                    Cfg.resumeNow(ctx)
                    s.pausedLeft = 0
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // 暂停倒计时
        LaunchedEffect(s.pausedLeft) {
            if (s.pausedLeft > 0) {
                while (s.pausedLeft > 0) {
                    delay(1000)
                    s.pausedLeft -= 1
                }
            }
        }
    }
}

// ==================== Tab 3：设置 ====================

@Composable
fun SettingsTab(
    activity: ComponentActivity,
    s: RingStateHolder,
    onShowAbout: () -> Unit,
    onShowWheel: () -> Unit
) {
    val ctx = activity
    s.PollLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 20.dp, bottom = 96.dp)
    ) {
        // ---------- 标题栏 ----------
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    "设置",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CForeground
                )
                Spacer(Modifier.height(4.dp))
                Text("手感、节奏与反馈调节", fontSize = 14.sp, color = CMutedFg)
            }
            IconButton(onClick = onShowAbout, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = "关于",
                    tint = CMutedFg,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(Modifier.height(20.dp))

            // ---------- 模式 ----------
            SectionLabel("滚动模式")
            SettingsCard {
                ModeRow(
                    Icons.Filled.Mouse,
                    "无障碍滑动",
                    "用无障碍发触摸手势，兼容性最好但可能不够连续",
                    s.mode == Cfg.MODE_SEGMENT
                ) {
                    s.mode = Cfg.MODE_SEGMENT
                    Cfg.setMode(ctx, s.mode)
                    VirtualPad.stop()
                }
                if (s.hasRoot) {
                    Divider(color = CBorder.copy(alpha = 0.3f))
                    ModeRow(
                        Icons.Filled.SportsMotorsports,
                        "方向盘（原生注入）",
                        "进程内常驻虚拟手柄，写 ABS_X，延迟接近传感器刷新",
                        s.mode == Cfg.MODE_WHEEL_PAD
                    ) {
                        s.mode = Cfg.MODE_WHEEL_PAD
                        Cfg.setMode(ctx, s.mode)
                        VirtualPad.reset()
                        // 进入方向盘模式时预热：提前建好手柄，避免第一次转动才建
                        try {
                            PadDeviceNative.load()
                            val t = Thread {
                                try {
                                    PadDeviceNative.ensureCreated()
                                } catch (e: Throwable) {
                                }
                            }
                            t.isDaemon = true
                            t.start()
                        } catch (e: Throwable) {
                        }
                    }
                }
            }

            // 方向盘参数（已拆到独立页面）
            if (s.mode == Cfg.MODE_WHEEL_PAD) {
                Spacer(Modifier.height(12.dp))
                SettingsCard {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onShowWheel() }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.SportsMotorsports,
                            contentDescription = null,
                            tint = CAccent,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("方向盘设置", fontSize = 15.sp, color = CForeground)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "查看方向盘 / 调转角与灵敏度",
                                fontSize = 12.sp,
                                color = CMutedFg
                            )
                        }
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = CMutedFg
                        )
                    }
                }
            }

            // ---------- 滑动方向 ----------
            SectionLabel("滑动方向")
            SettingsCard {
                ModeRow(
                    Icons.Filled.SwapVert,
                    "竖直滑动",
                    "上下滚动列表、刷信息流",
                    s.axis == Cfg.AXIS_VERTICAL
                ) {
                    s.axis = Cfg.AXIS_VERTICAL
                    Cfg.setAxis(ctx, s.axis)
                }
                Divider(color = CBorder.copy(alpha = 0.3f))
                ModeRow(
                    Icons.Filled.SwapHoriz,
                    "水平滑动",
                    "左右切换页面、翻卡片",
                    s.axis == Cfg.AXIS_HORIZONTAL
                ) {
                    s.axis = Cfg.AXIS_HORIZONTAL
                    Cfg.setAxis(ctx, s.axis)
                }
            }

            Spacer(Modifier.height(16.dp))

            Spacer(Modifier.height(16.dp))

        Spacer(Modifier.height(16.dp))

            // ---------- 参数 ----------
            SectionLabel("参数调节")
            SettingsCard {
                if (s.mode == Cfg.MODE_DRAG) {
                    SliderRow(
                        icon = Icons.Filled.Speed,
                        title = "灵敏度",
                        valueText = String.format("%.1f 倍", s.sens),
                        value = s.sens,
                        range = 0.2f..5.0f,
                        onChange = {
                            s.sens = it
                            Cfg.setSens(ctx, it)
                        }
                    )
Divider(color = CBorder.copy(alpha = 0.3f))
                    SliderRow(
                        icon = Icons.Filled.Waves,
                        title = "平滑窗口",
                        valueText = if (s.smoothWin == 0) "关闭" else "${s.smoothWin} 毫秒",
                        value = s.smoothWin.toFloat(),
                        range = 0f..300f,
                        onChange = {
                            s.smoothWin = ((it.toInt() / 10) * 10)
                            Cfg.setSmoothWindowMs(ctx, s.smoothWin)
                        }
                    )
                    Text(
                        "转动时先攒一小段时间再一次性滑动，越大越平滑但越迟滞",
                        fontSize = 11.sp,
                        color = CMutedFg,
                        modifier = Modifier.padding(start = 50.dp, bottom = 10.dp)
                    )
                    Divider(color = CBorder.copy(alpha = 0.3f))
                    SliderRow(
                        icon = Icons.Filled.Timer,
                        title = "保持按住",
                        valueText = "${s.dragHold} 毫秒",
                        value = s.dragHold.toFloat(),
                        range = 120f..2000f,
                        onChange = {
                            s.dragHold = ((it.toInt() / 20) * 20)
                            Cfg.setDragHoldMs(ctx, s.dragHold)
                        }
                    )
                    Text(
                        "停手后手指继续按这么久才抬起。这段时间内再转动会直接接着滑，不会重新起手",
                        fontSize = 11.sp,
                        color = CMutedFg,
                        modifier = Modifier.padding(start = 50.dp, bottom = 10.dp)
                    )
                    Divider(color = CBorder.copy(alpha = 0.3f))
                    SliderRow(
                        icon = Icons.Filled.Speed,
                        title = "每段时长",
                        valueText = "${s.dragSeg} 毫秒",
                        value = s.dragSeg.toFloat(),
                        range = 16f..120f,
                        onChange = {
                            s.dragSeg = ((it.toInt() / 4) * 4)
                            Cfg.setDragSegMs(ctx, s.dragSeg)
                        }
                    )
                    Text(
                        "每一下滑动的持续时间。越小衔接越密越跟手，但太小会碎",
                        fontSize = 11.sp,
                        color = CMutedFg,
                        modifier = Modifier.padding(start = 50.dp, bottom = 10.dp)
                    )
                } else {
                    SliderRow(
                        icon = Icons.Filled.Straighten,
                        title = "一格触发阈值",
                        valueText = String.format("%.0f", s.unit),
                        value = s.unit,
                        range = 20f..400f,
                        onChange = {
                            s.unit = it
                            Cfg.setWheelUnit(ctx, it)
                        }
                    )
                    Divider(color = CBorder.copy(alpha = 0.3f))
                    SliderRow(
                        icon = Icons.Filled.SwapVert,
                        title = "每格滑动像素",
                        valueText = "${s.step}",
                        value = s.step.toFloat(),
                        range = 90f..580f,
                        onChange = {
                            s.step = it.toInt()
                            Cfg.setStepPx(ctx, s.step)
                        }
                    )
                }
                Divider(color = CBorder.copy(alpha = 0.3f))
                SliderRow(
                    icon = Icons.Filled.FilterAlt,
                    title = "死区",
                    valueText = String.format("%.0f", s.dead),
                    value = s.dead,
                    range = 1f..115f,
                    onChange = {
                        s.dead = it
                        Cfg.setDeadzone(ctx, it)
                    }
                )
            }

            Spacer(Modifier.height(16.dp))

        Spacer(Modifier.height(16.dp))

            SectionLabel("触发节奏")
            SettingsCard {
                SliderRow(
                    icon = Icons.Filled.Timer,
                    title = "单次冷却",
                    valueText = if (s.cooldown == 0) "关闭" else "${s.cooldown} 毫秒",
                    value = s.cooldown.toFloat(),
                    range = 0f..5000f,
                    onChange = {
                        s.cooldown = (it.toInt() / 10) * 10
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
                    valueText = "${s.minInt} 毫秒",
                    value = s.minInt.toFloat(),
                    range = 30f..500f,
                    onChange = {
                        s.minInt = it.toInt()
                        Cfg.setMinIntervalMs(ctx, s.minInt)
                    }
                )
                Divider(color = CBorder.copy(alpha = 0.3f))
                SliderRow(
                    icon = Icons.Filled.Timelapse,
                    title = "手势时长",
                    valueText = "${s.gestDur} 毫秒",
                    value = s.gestDur.toFloat(),
                    range = 40f..400f,
                    onChange = {
                        s.gestDur = it.toInt()
                        Cfg.setGestureDurationMs(ctx, s.gestDur)
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
                    valueText = "${s.maxSwipe} 像素",
                    value = s.maxSwipe.toFloat(),
                    range = 90f..900f,
                    onChange = {
                        s.maxSwipe = it.toInt()
                        Cfg.setMaxSwipePx(ctx, s.maxSwipe)
                    }
                )
            }

            Spacer(Modifier.height(16.dp))

            // ---------- 快捷暂停 ----------

        Spacer(Modifier.height(16.dp))

            // ---------- 震动反馈 ----------
            SectionLabel("震动反馈")
            SettingsCard {
                SwitchRow(
                    icon = Icons.Filled.Vibration,
                    title = "转动震动",
                    subtitle = "每触发一次滑动震一下",
                    checked = s.haptic
                ) {
                    s.haptic = it
                    Cfg.setHaptic(ctx, it)
                }
                if (s.haptic) {
                    Divider(color = CBorder.copy(alpha = 0.3f))
                    SliderRow(
                        icon = Icons.Filled.FitnessCenter,
                        title = "震动强度",
                        valueText = "${s.hapticAmp}",
                        value = s.hapticAmp.toFloat(),
                        range = 10f..255f,
                        onChange = {
                            s.hapticAmp = it.toInt()
                            Cfg.setHapticAmp(ctx, s.hapticAmp)
                        }
                    )
                    Divider(color = CBorder.copy(alpha = 0.3f))
                    SliderRow(
                        icon = Icons.Filled.Timelapse,
                        title = "震动时长",
                        valueText = "${s.hapticMs} 毫秒",
                        value = s.hapticMs.toFloat(),
                        range = 5f..100f,
                        onChange = {
                            s.hapticMs = it.toInt()
                            Cfg.setHapticMs(ctx, s.hapticMs)
                        }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

        Spacer(Modifier.height(24.dp))

        // ---------- 使用提示 ----------
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
            "一顿一顿的时候调大死区或平滑窗口"
        ).forEach {
            Row(Modifier.padding(vertical = 3.dp)) {
                Text("· ", color = CAccent, fontSize = 13.sp)
                Text(it, color = CMutedFg, fontSize = 13.sp, lineHeight = 19.sp)
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
                    desc = "没有它无法执行滑动。注意：从最近任务划掉本应用会导致它失效，需重新开启",
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
private fun AboutRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    onClick: (() -> Unit)?
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick)
                else Modifier
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = CMutedFg,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            title,
            fontSize = 15.sp,
            color = CForeground,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            fontSize = 13.sp,
            color = if (onClick != null) CAccent else CMutedFg
        )
        if (onClick != null) {
            Spacer(Modifier.width(4.dp))
            Icon(
                androidx.compose.material.icons.Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = CMutedFg,
                modifier = Modifier.size(18.dp)
            )
        }
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

/** 简易 toast */
private fun toast(ctx: Context, msg: String) {
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}

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
/**
 * 方向盘可视化控件。
 * 实时显示当前累计角度，跟着变焦环转动。
 */
@Composable
fun SteeringWheelView(
    angleDeg: Float,
    maxDeg: Float,
    modifier: Modifier = Modifier
) {
    val safeMax = if (maxDeg < 1f) 540f else maxDeg
    val clamped = angleDeg.coerceIn(-safeMax, safeMax)
    // 满舵对应 ±135° 的视觉转角
    val visualDeg = (clamped / safeMax) * 135f

    Box(
        modifier = modifier.fillMaxWidth().height(220.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(196.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = size.minDimension / 2f

            // 外圈轨道
            drawCircle(
                color = CBorder.copy(alpha = 0.35f),
                radius = r - 4.dp.toPx(),
                style = Stroke(width = 5.dp.toPx())
            )

            // 正上方刻度
            val topA = -PI / 2
            drawLine(
                color = CAccent.copy(alpha = 0.85f),
                start = Offset(
                    cx + (cos(topA) * (r - 22.dp.toPx())).toFloat(),
                    cy + (sin(topA) * (r - 22.dp.toPx())).toFloat()
                ),
                end = Offset(
                    cx + (cos(topA) * (r - 1.dp.toPx())).toFloat(),
                    cy + (sin(topA) * (r - 1.dp.toPx())).toFloat()
                ),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )

            // 转动的方向盘
            rotate(degrees = visualDeg, pivot = Offset(cx, cy)) {
                val rim = r - 26.dp.toPx()

                drawCircle(
                    color = CForeground.copy(alpha = 0.92f),
                    radius = rim,
                    style = Stroke(width = 12.dp.toPx())
                )

                // 三根辐条
                listOf(
                    Offset(cx - rim, cy),
                    Offset(cx + rim, cy),
                    Offset(cx, cy + rim)
                ).forEach { end ->
                    drawLine(
                        color = CForeground.copy(alpha = 0.92f),
                        start = Offset(cx, cy),
                        end = end,
                        strokeWidth = 11.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                // 中心
                drawCircle(color = CAccent, radius = 15.dp.toPx())
                drawCircle(
                    color = CForeground.copy(alpha = 0.9f),
                    radius = 15.dp.toPx(),
                    style = Stroke(width = 2.5.dp.toPx())
                )

                // 12 点位置高亮块
                drawArc(
                    color = CAccent,
                    startAngle = -106f,
                    sweepAngle = 32f,
                    useCenter = false,
                    topLeft = Offset(cx - rim, cy - rim),
                    size = Size(rim * 2, rim * 2),
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        // 中心数值
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = clamped.toInt().toString() + "°",
                fontSize = 25.sp,
                color = CForeground,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "±" + safeMax.toInt() + "°",
                fontSize = 11.sp,
                color = CMutedFg
            )
        }
    }
}

/** 找变焦环传感器：先按名称模糊匹配，再按自定义 type 兜底 */
private fun findRingSensor(sm: android.hardware.SensorManager): android.hardware.Sensor? {
    val all = sm.getSensorList(android.hardware.Sensor.TYPE_ALL)
    for (s in all) {
        val n = s.name ?: continue
        if (n.lowercase().contains("optical")) return s
    }
    for (s in all) {
        if (s.type == RingForegroundService.TYPE_OPTICAL_TRACKING) return s
    }
    return null
}

/**
 * 方向盘独立页面。
 *
 * 上方是可交互的方向盘，跟着变焦环实时转动；
 * 下方是该模式的参数和手柄状态。
 */
@Composable
fun SteeringWheelScreen(activity: ComponentActivity, onBack: () -> Unit) {
    val ctx = activity
    BackHandler(enabled = true) { onBack() }

    var maxDeg by remember { mutableIntStateOf(Cfg.padMaxDeg(ctx)) }
    var gain by remember { mutableFloatStateOf(Cfg.padGain(ctx)) }
    var angle by remember { mutableFloatStateOf(VirtualPad.getShownAngle()) }
    var statusKey by remember { mutableIntStateOf(0) }

    // 实时读取方向盘的实际输出角度（插值后的值，转动更顺滑）
    LaunchedEffect(Unit) {
        while (true) {
            angle = VirtualPad.getShownAngle()
            delay(16)
        }
    }

    // 状态每 500ms 刷新
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            statusKey++
        }
    }

    // ---------- 页面自己订阅变焦环 ----------
    // 不经过控制服务：服务没启动、前台 App 识别失败、使用情况权限没给，
    // 都不影响这里。服务侧看到 padPreview 会主动让路，避免角度重复累加。
    val reverseDir = remember { Cfg.reverse(ctx) }
    var sensorOk by remember { mutableStateOf(true) }
    var liveDelta by remember { mutableFloatStateOf(0f) }
    var lastUiMs by remember { mutableLongStateOf(0L) }
    DisposableEffect(Unit) {
        RingForegroundService.padPreview = true
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
        val sensor = sm?.let { findRingSensor(it) }
        sensorOk = sensor != null
        var lastCounter = Float.NaN
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent?) {
                val v = e?.values ?: return
                if (v.size < 3) return
                val c = v[2]
                if (lastCounter.isNaN()) {
                    lastCounter = c
                    return
                }
                val d = c - lastCounter
                lastCounter = c
                // 诊断用：每 120ms 更新一次页面上的原始 Δ，方便判断是没数据还是没输出
                val nowMs = android.os.SystemClock.elapsedRealtime()
                if (nowMs - lastUiMs > 120L) {
                    lastUiMs = nowMs
                    liveDelta = d
                }
                if (Math.abs(d) < 1f) return
                val step = (if (reverseDir) -d else d) * gain
                VirtualPad.turn(ctx, step, maxDeg.toFloat())
            }

            override fun onAccuracyChanged(s: android.hardware.Sensor?, accuracy: Int) {}
        }
        if (sm != null && sensor != null) {
            sm.registerListener(
                listener, sensor,
                android.hardware.SensorManager.SENSOR_DELAY_GAME
            )
        }
        onDispose {
            sm?.unregisterListener(listener)
            RingForegroundService.padPreview = false
        }
    }

    // 进页就确保手柄存在
    LaunchedEffect(Unit) {
        VirtualPad.ensureReady(ctx)
    }

    val status = remember(statusKey) { VirtualPad.statusText() }
    val lastErr = remember(statusKey) { PadDeviceNative.getLastError() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        // ---------- 顶栏 ----------
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = CForeground)
            }
            Text(
                "方向盘",
                fontSize = 19.sp,
                color = CForeground,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                VirtualPad.reset()
                VirtualPad.push(ctx, maxDeg.toFloat())
            }) {
                Text("回正", color = CAccent)
            }
        }

        // ---------- 方向盘本体 ----------
        SteeringWheelView(
            angleDeg = angle,
            maxDeg = maxDeg.toFloat()
        )

        // ---------- 参数 ----------
        Column(Modifier.padding(horizontal = 16.dp)) {
            SectionLabel("方向盘参数")
            SettingsCard {
                SliderRow(
                    icon = Icons.Filled.RotateRight,
                    title = "最大转角",
                    valueText = "±" + maxDeg + "°",
                    value = maxDeg.toFloat(),
                    range = 90f..900f,
                    onChange = {
                        maxDeg = (it.toInt() / 10) * 10
                        Cfg.setPadMaxDeg(ctx, maxDeg)
                        VirtualPad.reset()
                    }
                )
                Text(
                    "方向盘左右各能打多少度。真车一般一圈半约 540°",
                    fontSize = 11.sp,
                    color = CMutedFg,
                    modifier = Modifier.padding(start = 50.dp, bottom = 10.dp)
                )
                Divider(color = CBorder.copy(alpha = 0.3f))
                SliderRow(
                    icon = Icons.Filled.Speed,
                    title = "转动灵敏度",
                    valueText = String.format("%.2f 倍", gain),
                    value = gain,
                    range = 0.05f..3.0f,
                    onChange = {
                        gain = it
                        Cfg.setPadGain(ctx, it)
                    }
                )
                Text(
                    "转一格等于多少度。觉得跳就往下调，觉得转半天不动就往上调",
                    fontSize = 11.sp,
                    color = CMutedFg,
                    modifier = Modifier.padding(start = 50.dp, bottom = 10.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            // ---------- 手柄状态 ----------
            SectionLabel("手柄状态")
            SettingsCard {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            status,
                            fontSize = 15.sp,
                            color = if (status == "手柄已创建") CAccent else CForeground
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (sensorOk) "传感器：已连接　最近 Δ = ${liveDelta.toInt()}"
                            else "传感器：未找到，转环不会有数据",
                            fontSize = 11.sp,
                            color = if (sensorOk) CMutedFg else CDestructive
                        )
                        if (lastErr != "无") {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "错误: " + lastErr,
                                fontSize = 11.sp,
                                color = CMutedFg
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    SmallBtn("重建") {
                        Thread {
                            try {
                                PadDeviceNative.reset()
                                PadDeviceNative.ensureCreated()
                            } catch (e: Throwable) {
                            }
                        }.start()
                        statusKey++
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun AboutScreen(activity: ComponentActivity, onBack: () -> Unit) {
    val ctx = activity
    var versionName by remember { mutableStateOf(UpdateChecker.currentVersion(ctx)) }
    var checking by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<String?>(null) }
    var updateUrl by remember { mutableStateOf(UpdateChecker.URL_RELEASES) }

    // 系统返回键 → 关闭关于页
    BackHandler(enabled = true) { onBack() }

    // 进入页面自动检查一次
    LaunchedEffect(Unit) {
        UpdateChecker.check(ctx, object : UpdateChecker.Callback {
            override fun onUpdate(latest: String, url: String, log: String) {
                updateInfo = latest
                updateUrl = url
            }

            override fun onLatest(cur: String) {}

            override fun onFail(reason: String) {}
        })
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        // 顶栏
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = CForeground
                )
            }
            Text(
                "关于",
                fontSize = 19.sp,
                fontWeight = FontWeight.Medium,
                color = CForeground
            )
        }

        Column(Modifier.padding(horizontal = 16.dp)) {
            // 应用信息
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(CAccent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(R.mipmap.app_icon_small),
                        contentDescription = null,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(16.dp))
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        "变焦环控制",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CForeground
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "RingCtl  v$versionName  Beta",
                        fontSize = 13.sp,
                        color = CMutedFg
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "把小米 17 Ultra 徕卡版的物理变焦环，当作通用旋钮使用。",
                fontSize = 13.sp,
                color = CMutedFg,
                lineHeight = 20.sp
            )

            Spacer(Modifier.height(24.dp))

            // 更新
            SectionLabel("更新")
            SettingsCard {
                if (updateInfo != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { UpdateChecker.openUrl(ctx, updateUrl) }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.SystemUpdate,
                            contentDescription = null,
                            tint = CAccent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "发现新版本 $updateInfo",
                                fontSize = 15.sp,
                                color = CAccent,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(2.dp))
                            Text("点击前往下载", fontSize = 12.sp, color = CMutedFg)
                        }
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = CMutedFg,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Divider(color = CBorder.copy(alpha = 0.3f))
                }
                AboutRow(
                    icon = Icons.Filled.CheckCircle,
                    title = "检查更新",
                    value = if (checking) "检查中…" else "当前最新",
                    onClick = {
                        if (!checking) {
                            checking = true
                            UpdateChecker.check(ctx, object : UpdateChecker.Callback {
                                override fun onUpdate(latest: String, url: String, log: String) {
                                    checking = false
                                    updateInfo = latest
                                    updateUrl = url
                                }

                                override fun onLatest(cur: String) {
                                    checking = false
                                    updateInfo = null
                                    toast(ctx, "已是最新版本 v$cur")
                                }

                                override fun onFail(reason: String) {
                                    checking = false
                                    toast(ctx, "检查失败：$reason")
                                }
                            })
                        }
                    }
                )
            }

            Spacer(Modifier.height(16.dp))

            // 作者
            SectionLabel("作者")
            SettingsCard {
                AboutRow(
                    icon = Icons.Filled.Code,
                    title = "GitHub",
                    value = UpdateChecker.REPO,
                    onClick = { UpdateChecker.openUrl(ctx, UpdateChecker.URL_REPO) }
                )
                Divider(color = CBorder.copy(alpha = 0.3f))
                AboutRow(
                    icon = Icons.Filled.Person,
                    title = "酷安",
                    value = UpdateChecker.COOLAPK_NAME,
                    onClick = { UpdateChecker.openUrl(ctx, UpdateChecker.URL_COOLAPK) }
                )
            }

            Spacer(Modifier.height(16.dp))

            // 开源
            SectionLabel("开源致谢")
            SettingsCard {
                Text(
                    "本项目的传感器方案参考了以下开源项目：",
                    fontSize = 12.sp,
                    color = CMutedFg,
                    modifier = Modifier.padding(16.dp)
                )
                AboutRow(
                    icon = Icons.Filled.Star,
                    title = "HyperZoomRing",
                    value = "NextAlone",
                    onClick = {
                        UpdateChecker.openUrl(ctx, "https://github.com/NextAlone/HyperZoomRing")
                    }
                )
                Divider(color = CBorder.copy(alpha = 0.3f))
                AboutRow(
                    icon = Icons.Filled.Star,
                    title = "OmniRing",
                    value = "TvTjiesen",
                    onClick = {
                        UpdateChecker.openUrl(ctx, "https://github.com/TvTjiesen/OmniRing")
                    }
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                "仅限个人学习使用",
                fontSize = 12.sp,
                color = CMutedFg,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}
// ==================== Tab 2：应用 ====================

@Composable
fun AppTabHost(activity: ComponentActivity, s: RingStateHolder) {
    AppTab(activity)
}

/**
 * 页面路由：
 *   0 = 已设置列表
 *   1 = 添加应用（搜索）
 *   2 = 某个 App 的独立设置
 */
@Composable
fun AppTab(activity: ComponentActivity) {
    var page by remember { mutableIntStateOf(0) }
    var editingPkg by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    when (page) {
        1 -> AddAppScreen(
            activity = activity,
            onBack = { page = 0; refreshKey++ }
        )
        2 -> AppDetailScreen(
            activity = activity,
            pkg = editingPkg,
            onBack = { page = 0; refreshKey++ }
        )
        else -> ConfiguredAppList(
            activity = activity,
            refreshKey = refreshKey,
            onAdd = { page = 1 },
            onOpen = { pkg -> editingPkg = pkg; page = 2 }
        )
    }
}

/** 已设置列表：只显示动过的 App */
@Composable
private fun ConfiguredAppList(
    activity: ComponentActivity,
    refreshKey: Int,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit
) {
    val ctx = activity

    // 可变状态：切换开关后立即刷新，不用退出页面重进
    var onSet by remember(refreshKey) { mutableStateOf(AppCfg.enabledPkgs(ctx)) }
    var offSet by remember(refreshKey) { mutableStateOf(AppCfg.disabledPkgs(ctx)) }

    // 只查询被设置过的那几个 App，不做全量扫描
    var configured by remember(refreshKey) {
        mutableStateOf(
            AppList.loadByPkgs(ctx, HashSet<String>().apply {
                addAll(onSet)
                addAll(offSet)
            }).sortedBy { it.label }
        )
    }

    /** 重新读取配置并刷新列表 */
    fun reload() {
        onSet = AppCfg.enabledPkgs(ctx)
        offSet = AppCfg.disabledPkgs(ctx)
        configured = AppList.loadByPkgs(ctx, HashSet<String>().apply {
            addAll(onSet)
            addAll(offSet)
        }).sortedBy { it.label }
    }


    Column(Modifier.fillMaxSize()) {
        // ---------- 标题 ----------
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 12.dp, top = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "按应用设置",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CForeground
                )
            }
            TextButton(onClick = { onAdd() }) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = CAccent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("添加", fontSize = 14.sp, color = CAccent)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "为单个 App 指定是否响应变焦环",
            fontSize = 14.sp,
            color = CMutedFg,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(16.dp))

        // ---------- 说明 ----------
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(CCard)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text("说明", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = CForeground)
            Spacer(Modifier.height(6.dp))
            Text(
                "· 全局开关关闭时，默认所有 App 都不生效\n· 在这里单独打开的 App，会直接生效（不受全局开关影响）\n· 未单独设置的 App，跟随全局开关",
                fontSize = 12.sp,
                color = CMutedFg,
                lineHeight = 19.sp
            )
        }

        Spacer(Modifier.height(20.dp))

        // ---------- 已设置列表 ----------
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "已设置的应用",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = CForeground,
                modifier = Modifier.weight(1f)
            )
            if (configured.isNotEmpty()) {
                Text("${configured.size} 个", fontSize = 12.sp, color = CMutedFg)
            }
        }
        Spacer(Modifier.height(8.dp))

        if (configured.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CCard)
                    .padding(vertical = 28.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有单独设置的应用", fontSize = 14.sp, color = CMutedFg)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "点下方「添加应用」挑选",
                        fontSize = 12.sp,
                        color = CMutedFg.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(configured.size) { idx ->
                    val app = configured[idx]
                    val st = when {
                        onSet.contains(app.pkg) -> 1
                        offSet.contains(app.pkg) -> -1
                        else -> 0
                    }
                    AppRow(
                        app = app,
                        state = st,
                        onClick = { onOpen(app.pkg) },
                        onToggle = { on ->
                            AppCfg.setAppState(ctx, app.pkg, if (on) 1 else -1)
                            reload()
                        },
                        onDelete = {
                            AppCfg.clearAllOverrides(ctx, app.pkg)
                            AppCfg.setAppState(ctx, app.pkg, 0)
                            reload()
                        }
                    )
                    Divider(color = CBorder.copy(alpha = 0.2f))
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }

    }
}

/** 添加应用：全量列表 + 搜索 */
@Composable
private fun AddAppScreen(activity: ComponentActivity, onBack: () -> Unit) {
    val ctx = activity
    var apps by remember { mutableStateOf<List<AppList.Item>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var keyword by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    BackHandler(enabled = true) { onBack() }

    LaunchedEffect(showSystem, refreshKey) {
        loading = true
        apps = withContext(Dispatchers.IO) { AppList.load(ctx, showSystem) }
        loading = false
    }

    var onSet by remember(refreshKey) { mutableStateOf(AppCfg.enabledPkgs(ctx)) }
    var offSet by remember(refreshKey) { mutableStateOf(AppCfg.disabledPkgs(ctx)) }

    val filtered = remember(apps, keyword) {
        if (keyword.isBlank()) apps
        else apps.filter {
            it.label.contains(keyword, true) || it.pkg.contains(keyword, true)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 顶栏
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = CForeground)
            }
            Text("添加应用", fontSize = 19.sp, fontWeight = FontWeight.Medium, color = CForeground)
        }

        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = { Text("搜索应用", color = CMutedFg, fontSize = 14.sp) },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null, tint = CMutedFg)
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CAccent,
                unfocusedBorderColor = CBorder,
                focusedTextColor = CForeground,
                unfocusedTextColor = CForeground,
                cursorColor = CAccent
            ),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(Modifier.height(8.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "显示系统应用",
                fontSize = 13.sp,
                color = CMutedFg,
                modifier = Modifier
                    .weight(1f)
                    .clickable { showSystem = !showSystem }
            )
            Switch(
                checked = showSystem,
                onCheckedChange = { showSystem = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = COnAccent,
                    checkedTrackColor = CAccent
                )
            )
        }

        Spacer(Modifier.height(4.dp))

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CAccent, modifier = Modifier.size(28.dp))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered.size) { idx ->
                    val app = filtered[idx]
                    val st = when {
                        onSet.contains(app.pkg) -> 1
                        offSet.contains(app.pkg) -> -1
                        else -> 0
                    }
                    AppRow(
                        app = app,
                        state = st,
                        onClick = { AppCfg.setAppState(ctx, app.pkg, if (st == 1) 0 else 1); refreshKey++ },
                        onToggle = { on ->
                            AppCfg.setAppState(ctx, app.pkg, if (on) 1 else 0)
                            refreshKey++
                        }
                    )
                    Divider(color = CBorder.copy(alpha = 0.2f))
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** 单个 App 的完整独立设置 */
@Composable
private fun AppDetailScreen(activity: ComponentActivity, pkg: String?, onBack: () -> Unit) {
    val ctx = activity
    BackHandler(enabled = true) { onBack() }

    if (pkg == null) {
        onBack()
        return
    }

    var apps by remember { mutableStateOf<List<AppList.Item>>(emptyList()) }
    LaunchedEffect(pkg) {
        apps = withContext(Dispatchers.IO) {
            AppList.load(ctx, true) + AppList.load(ctx, false)
        }
    }
    val app = remember(apps, pkg) { apps.firstOrNull { it.pkg == pkg } }

    var state by remember { mutableIntStateOf(AppCfg.getAppState(ctx, pkg)) }

    // 参数是否被覆盖
    var ovMode by remember { mutableStateOf(AppCfg.hasOverride(ctx, pkg, "mode")) }
    var ovAxis by remember { mutableStateOf(AppCfg.hasOverride(ctx, pkg, "axis")) }
    var ovSens by remember { mutableStateOf(AppCfg.hasOverride(ctx, pkg, "sens")) }
    var ovUnit by remember { mutableStateOf(AppCfg.hasOverride(ctx, pkg, "unit")) }
    var ovStep by remember { mutableStateOf(AppCfg.hasOverride(ctx, pkg, "step")) }
    var ovDead by remember { mutableStateOf(AppCfg.hasOverride(ctx, pkg, "dead")) }
    var ovFixed by remember { mutableStateOf(AppCfg.hasOverride(ctx, pkg, "fixed")) }
    var ovFixedSide by remember { mutableStateOf(AppCfg.hasOverride(ctx, pkg, "fixed_side")) }

    // 参数值（有覆盖读覆盖，否则读全局作为初值）
    var mode by remember { mutableIntStateOf(AppCfg.getInt(ctx, pkg, "mode", Cfg.mode(ctx))) }
    var axis by remember { mutableIntStateOf(AppCfg.getInt(ctx, pkg, "axis", Cfg.axis(ctx))) }
    var sens by remember { mutableFloatStateOf(AppCfg.getFloat(ctx, pkg, "sens", Cfg.sens(ctx))) }
    var unit by remember { mutableFloatStateOf(AppCfg.getFloat(ctx, pkg, "unit", Cfg.wheelUnit(ctx))) }
    var step by remember { mutableIntStateOf(AppCfg.getInt(ctx, pkg, "step", Cfg.stepPx(ctx))) }
    var dead by remember { mutableFloatStateOf(AppCfg.getFloat(ctx, pkg, "dead", Cfg.deadzone(ctx))) }
    var fixedDir by remember { mutableStateOf(AppCfg.getBool(ctx, pkg, "fixed", Cfg.fixedDir(ctx))) }
    var fixedSide by remember { mutableIntStateOf(AppCfg.getInt(ctx, pkg, "fixed_side", Cfg.fixedDirSide(ctx))) }

    fun refreshOv() {
        ovMode = AppCfg.hasOverride(ctx, pkg, "mode")
        ovAxis = AppCfg.hasOverride(ctx, pkg, "axis")
        ovSens = AppCfg.hasOverride(ctx, pkg, "sens")
        ovUnit = AppCfg.hasOverride(ctx, pkg, "unit")
        ovStep = AppCfg.hasOverride(ctx, pkg, "step")
        ovDead = AppCfg.hasOverride(ctx, pkg, "dead")
        ovFixed = AppCfg.hasOverride(ctx, pkg, "fixed")
        ovFixedSide = AppCfg.hasOverride(ctx, pkg, "fixed_side")
    }

    /** 把全部参数切成自定义（保留当前显示值） */
    fun enableAllOverrides() {
        AppCfg.setInt(ctx, pkg, "mode", mode)
        AppCfg.setInt(ctx, pkg, "axis", axis)
        AppCfg.setFloat(ctx, pkg, "sens", sens)
        AppCfg.setFloat(ctx, pkg, "unit", unit)
        AppCfg.setInt(ctx, pkg, "step", step)
        AppCfg.setFloat(ctx, pkg, "dead", dead)
        AppCfg.setBool(ctx, pkg, "fixed", fixedDir)
        AppCfg.setInt(ctx, pkg, "fixed_side", fixedSide)
        refreshOv()
    }

    /** 把全局当前值复制到这个 App（并切成自定义） */
    fun copyGlobal() {
        mode = Cfg.mode(ctx)
        axis = Cfg.axis(ctx)
        sens = Cfg.sens(ctx)
        unit = Cfg.wheelUnit(ctx)
        step = Cfg.stepPx(ctx)
        dead = Cfg.deadzone(ctx)
        fixedDir = Cfg.fixedDir(ctx)
        fixedSide = Cfg.fixedDirSide(ctx)
        enableAllOverrides()
    }


    Column(Modifier.fillMaxSize()) {
        // 顶栏
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = CForeground)
            }
            Text(
                app?.label ?: pkg,
                fontSize = 19.sp,
                fontWeight = FontWeight.Medium,
                color = CForeground,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                AppCfg.clearAllOverrides(ctx, pkg)
                mode = Cfg.mode(ctx)
                axis = Cfg.axis(ctx)
                sens = Cfg.sens(ctx)
                unit = Cfg.wheelUnit(ctx)
                step = Cfg.stepPx(ctx)
                dead = Cfg.deadzone(ctx)
                refreshOv()
            }) {
                Text("全部跟随全局", fontSize = 12.sp, color = CAccent)
            }
            TextButton(onClick = { copyGlobal() }) {
                Text("复制全局", fontSize = 12.sp, color = CAccent)
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // 应用信息
            if (app != null) {
                AppIconLarge(app)
                Spacer(Modifier.height(8.dp))
                Text(
                    pkg,
                    fontSize = 11.sp,
                    color = CMutedFg,
                    modifier = Modifier.padding(start = 4.dp)
                )
                Spacer(Modifier.height(20.dp))
            }

            // ---------- 响应开关 ----------
            SectionLabel("是否响应")
            SettingsCard {
                ModeRow(
                    Icons.Filled.PowerSettingsNew,
                    "单独特定启用",
                    "打开后直接生效，下面的参数会自动切成自定义",
                    state == 1
                ) {
                    state = if (state == 1) 0 else 1
                    AppCfg.setAppState(ctx, pkg, state)
                    // 启用独立设置时，把全部参数切成自定义
                    if (state == 1) {
                        enableAllOverrides()
                    }
                }
                Divider(color = CBorder.copy(alpha = 0.3f))
                ModeRow(
                    Icons.Filled.Block,
                    "单独禁用",
                    "无论全局开关如何，这个 App 都不响应",
                    state == -1
                ) {
                    state = if (state == -1) 0 else -1
                    AppCfg.setAppState(ctx, pkg, state)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when (state) {
                    1 -> "当前：已单独启用（强生效）"
                    -1 -> "当前：已单独禁用（强屏蔽）"
                    else -> "当前：未单独设置，跟随全局开关"
                },
                fontSize = 12.sp,
                color = CMutedFg,
                modifier = Modifier.padding(start = 4.dp)
            )

            Spacer(Modifier.height(20.dp))

            // ---------- 滚动模式 ----------
            OvHeader(
                "滚动模式",
                ovMode,
                onFollow = {
                    AppCfg.clearOverride(ctx, pkg, "mode")
                    mode = Cfg.mode(ctx)
                    refreshOv()
                },
                onCustom = {
                    AppCfg.setInt(ctx, pkg, "mode", mode)
                    refreshOv()
                }
            )
            SettingsCard {
                ModeRow(Icons.Filled.Mouse, "无障碍滑动", "用无障碍发触摸手势", mode == Cfg.MODE_SEGMENT) {
                    mode = Cfg.MODE_SEGMENT
                    if (ovMode) AppCfg.setInt(ctx, pkg, "mode", mode)
                }
                Divider(color = CBorder.copy(alpha = 0.3f))
                ModeRow(
                    Icons.Filled.SportsMotorsports,
                    "方向盘（原生注入）",
                    "这个 App 用虚拟手柄摇杆，适合赛车游戏",
                    mode == Cfg.MODE_WHEEL_PAD
                ) {
                    mode = Cfg.MODE_WHEEL_PAD
                    if (ovMode) AppCfg.setInt(ctx, pkg, "mode", mode)
                    // 选了方向盘就立刻把柄建好，不用等转环
                    Thread {
                        try {
                            PadDeviceNative.load()
                            PadDeviceNative.ensureCreated()
                        } catch (e: Throwable) {
                        }
                    }.start()
                }
            }

            Spacer(Modifier.height(20.dp))

            // ---------- 滑动方向 ----------
            OvHeader(
                "滑动方向",
                ovAxis,
                onFollow = {
                    AppCfg.clearOverride(ctx, pkg, "axis")
                    axis = Cfg.axis(ctx)
                    refreshOv()
                },
                onCustom = {
                    AppCfg.setInt(ctx, pkg, "axis", axis)
                    refreshOv()
                }
            )
            SettingsCard {
                ModeRow(
                    Icons.Filled.SwapVert,
                    "竖直滑动",
                    "上下滚动列表、刷信息流",
                    axis == Cfg.AXIS_VERTICAL
                ) {
                    axis = Cfg.AXIS_VERTICAL
                    if (ovAxis) AppCfg.setInt(ctx, pkg, "axis", axis)
                }
                Divider(color = CBorder.copy(alpha = 0.3f))
                ModeRow(
                    Icons.Filled.SwapHoriz,
                    "水平滑动",
                    "左右切换页面、翻卡片",
                    axis == Cfg.AXIS_HORIZONTAL
                ) {
                    axis = Cfg.AXIS_HORIZONTAL
                    if (ovAxis) AppCfg.setInt(ctx, pkg, "axis", axis)
                }
            }

            Spacer(Modifier.height(20.dp))

            // ---------- 固定方向 ----------
            OvHeader(
                "固定方向",
                ovFixed,
                onFollow = {
                    AppCfg.clearOverride(ctx, pkg, "fixed")
                    fixedDir = Cfg.fixedDir(ctx)
                    refreshOv()
                },
                onCustom = {
                    AppCfg.setBool(ctx, pkg, "fixed", fixedDir)
                    refreshOv()
                }
            )
            SettingsCard {
                SwitchRow(
                    icon = Icons.Filled.SwapHoriz,
                    title = "固定方向",
                    subtitle = "不管往前还是往后转，都只往一个方向滚动",
                    checked = fixedDir
                ) {
                    fixedDir = it
                    if (ovFixed) AppCfg.setBool(ctx, pkg, "fixed", it)
                }
                if (fixedDir) {
                    Divider(color = CBorder.copy(alpha = 0.3f))
                    ModeRow(
                        Icons.Filled.ArrowUpward,
                        "固定往正方向",
                        "正转反转都按正方向滚",
                        fixedSide == Cfg.FIXED_POSITIVE
                    ) {
                        fixedSide = Cfg.FIXED_POSITIVE
                        if (ovFixedSide) AppCfg.setInt(ctx, pkg, "fixed_side", fixedSide)
                    }
                    Divider(color = CBorder.copy(alpha = 0.3f))
                    ModeRow(
                        Icons.Filled.ArrowDownward,
                        "固定往反方向",
                        "正转反转都按反方向滚",
                        fixedSide == Cfg.FIXED_NEGATIVE
                    ) {
                        fixedSide = Cfg.FIXED_NEGATIVE
                        if (ovFixedSide) AppCfg.setInt(ctx, pkg, "fixed_side", fixedSide)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ---------- 参数 ----------
            OvHeader(
                "灵敏度",
                ovSens,
                onFollow = {
                    AppCfg.clearOverride(ctx, pkg, "sens")
                    sens = Cfg.sens(ctx)
                    refreshOv()
                },
                onCustom = {
                    AppCfg.setFloat(ctx, pkg, "sens", sens)
                    refreshOv()
                }
            )
            SettingsCard {
                SliderRow(
                    icon = Icons.Filled.Speed,
                    title = "灵敏度",
                    valueText = String.format("%.1f 倍", sens),
                    value = sens,
                    range = 0.2f..5.0f,
                    onChange = {
                        sens = it
                        if (ovSens) AppCfg.setFloat(ctx, pkg, "sens", it)
                    }
                )
                Divider(color = CBorder.copy(alpha = 0.3f))
                SliderRow(
                    icon = Icons.Filled.Straighten,
                    title = "一格触发阈值",
                    valueText = String.format("%.0f", unit),
                    value = unit,
                    range = 20f..400f,
                    onChange = {
                        unit = it
                        if (ovUnit) AppCfg.setFloat(ctx, pkg, "unit", it)
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
                        if (ovStep) AppCfg.setInt(ctx, pkg, "step", step)
                    }
                )
                Divider(color = CBorder.copy(alpha = 0.3f))
                SliderRow(
                    icon = Icons.Filled.Tune,
                    title = "死区",
                    valueText = String.format("%.1f", dead),
                    value = dead,
                    range = 0f..30f,
                    onChange = {
                        dead = it
                        if (ovDead) AppCfg.setFloat(ctx, pkg, "dead", it)
                    }
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "开启「自定义」后，这个 App 才使用自己的参数；「跟随全局」时随时同步全局设置。",
                fontSize = 12.sp,
                color = CMutedFg,
                modifier = Modifier.padding(start = 4.dp)
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

/** 参数分组头：标题 + 跟随全局/自定义 切换 */
@Composable
private fun OvHeader(
    title: String,
    isCustom: Boolean,
    onFollow: () -> Unit,
    onCustom: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = CForeground,
            modifier = Modifier.weight(1f)
        )
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(CMuted)
                .padding(2.dp)
        ) {
            // 跟随全局
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (!isCustom) CAccent else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { if (isCustom) onFollow() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    "跟随全局",
                    fontSize = 11.sp,
                    color = if (!isCustom) COnAccent else CMutedFg
                )
            }
            // 自定义
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isCustom) CAccent else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { if (!isCustom) onCustom() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    "自定义",
                    fontSize = 11.sp,
                    color = if (isCustom) COnAccent else CMutedFg
                )
            }
        }
    }
}
@Composable
private fun AppIconLarge(app: AppList.Item) {
    val bmp = remember(app.pkg) {
        app.icon?.let { runCatching { drawableToBitmap(it).asImageBitmap() }.getOrNull() }
    }
    Box(Modifier.padding(start = 4.dp)) {
        if (bmp != null) {
            androidx.compose.foundation.Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
            )
        } else {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(CMuted),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    app.label.take(1),
                    fontSize = 26.sp,
                    color = CAccent,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun AppRow(
    app: AppList.Item,
    state: Int,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标：圆角裁切作用于图片本身，外面不再叠容器圆角
        val iconBmp = remember(app.pkg) {
            app.icon?.let { runCatching { drawableToBitmap(it).asImageBitmap() }.getOrNull() }
        }
        if (iconBmp != null) {
            androidx.compose.foundation.Image(
                bitmap = iconBmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
        } else {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(CMuted),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    app.label.take(1),
                    fontSize = 18.sp,
                    color = CAccent,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                fontSize = 15.sp,
                color = CForeground,
                maxLines = 1
            )
            // 只有设置过的才显示状态文字
            if (state != 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    if (state == 1) "已启用" else "已禁用",
                    fontSize = 11.sp,
                    color = if (state == 1) CAccent else CDestructive
                )
            }
        }

        Spacer(Modifier.width(4.dp))

        // 删除：清掉这个 App 的所有独立设置，回到跟随全局
        if (onDelete != null) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = "清除设置",
                    tint = CMutedFg,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Switch(
            checked = state == 1,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = COnAccent,
                checkedTrackColor = CAccent,
                uncheckedThumbColor = CMutedFg,
                uncheckedTrackColor = CMuted
            )
        )
    }
}

private fun drawableToBitmap(d: android.graphics.drawable.Drawable): android.graphics.Bitmap {
    val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else 48
    val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else 48
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    d.setBounds(0, 0, w, h)
    d.draw(canvas)
    return bmp
}
