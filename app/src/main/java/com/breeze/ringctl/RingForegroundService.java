package com.breeze.ringctl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.widget.RemoteViews;
import android.util.Log;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 前台服务：读变焦环，转成滑动手势。
 */
public class RingForegroundService extends Service implements SensorEventListener {

    private static final String TAG = "RingCtl";
    private static final String CHANNEL_ID = "ring_ctl";
    private static final int NOTI_ID = 1001;
    /** 通知栏：切换滑动方向 */
    public static final String ACTION_TOGGLE_AXIS = "com.breeze.ringctl.TOGGLE_AXIS";
    /** 通知栏：启用/停用 */
    public static final String ACTION_TOGGLE_ENABLED = "com.breeze.ringctl.TOGGLE_ENABLED";

    public static final int TYPE_OPTICAL_TRACKING = 33171136;

    private static final long TICK_ACTIVE_MS = 16;
    private static final long TICK_IDLE_MS = 60;
    private static final long IDLE_THRESHOLD_MS = 500;
    private static final int MAX_INFLIGHT = 2;

    public static volatile boolean running = false;
    /**
     * 方向盘预览开关。
     *
     * 打开方向盘页面时置 true，离开置 false。
     * 作用有两个：
     *   1. 让「自己这个 App」也能收到变焦环数据（否则页面上的方向盘永远不动）
     *   2. 强制走方向盘分支，不受当前全局模式 / 冷却时间影响
     */
    public static volatile boolean padPreview = false;
    public static volatile String lastState = "未启动";
    public static volatile float dbgDelta = 0f;
    public static volatile float dbgPending = 0f;
    public static volatile boolean dbgPaused = false;

    private SensorManager sm;
    private Sensor ringSensor;
    private Handler handler;
    private HandlerThread emitThread;

    private float lastCounter = Float.NaN;

    private volatile float pendingPx = 0f;
    private volatile float wheelAccum = 0f;
    private volatile long lastDataMs = 0L;
    private long lastEmitMs = 0L;
    /** 方向防抖：连续多少帧同向才认 */
    private static final int DIR_CONFIRM_FRAMES = 2;
    private int lastDir = 0;
    private int dirConfirm = 0;
    /** Root 滚轮模式：上次发出滚轮的时间 */
    private long lastRootWheelMs = 0L;
    private volatile long cooldownUntilMs = 0L;
    private long lastHapticMs = 0L;
    private Vibrator vibrator;
    private final AtomicInteger inflight = new AtomicInteger(0);
    private final AtomicBoolean tickScheduled = new AtomicBoolean(false);
    private Thread keepAliveThread;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        VirtualPad.init(this);
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        startForegroundCompat();

        emitThread = new HandlerThread("ring-emit");
        emitThread.start();
        handler = new Handler(emitThread.getLooper());

        registerSensor();
        running = true;
        scheduleTick(0);
        startKeepAlive();
    }

@Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_TOGGLE_AXIS.equals(action)) {
                int cur = Cfg.axis(this);
                int next = (cur == Cfg.AXIS_VERTICAL)
                        ? Cfg.AXIS_HORIZONTAL
                        : Cfg.AXIS_VERTICAL;
                Cfg.setAxis(this, next);
                refreshNotification();
                return START_STICKY;
            }
            if (ACTION_TOGGLE_ENABLED.equals(action)) {
                Cfg.setEnabled(this, !Cfg.enabled(this));
                refreshNotification();
                return START_STICKY;
            }
        }
        return START_STICKY;
    }

    /**
     * 启动前台服务。
     * Android 14+ 必须显式声明类型，否则系统可能直接终止。
     */
    private void startForegroundCompat() {
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTI_ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTI_ID, n);
            }
        } catch (Exception e) {
            try {
                startForeground(NOTI_ID, n);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 刷新常驻通知。
     *
     * 关键：用 startForeground 而不是 notify()。
     * 小米等 ROM 移掉通知后，单纯 notify 不会恢复前台身份，
     * 会导致服务被视为「没有前台通知」而被清理。
     */
    private void refreshNotification() {
        try {
            startForegroundCompat();
        } catch (Exception ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            if (handler != null) {
                handler.removeCallbacksAndMessages(null);
            }
        } catch (Exception ignored) {
        }
        if (sm != null) {
            sm.unregisterListener(this);
        }
        if (emitThread != null) {
            emitThread.quitSafely();
        }
        stopForeground(true);
        super.onDestroy();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "变焦环控制", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(ch);
            }
        }
    }

/**
     * 诊断用：当前前台 App + 是否生效。
     */
    private String buildAppLine() {
        String pkg = ForegroundApp.get(this);
        if (pkg == null || pkg.length() == 0) {
            return "当前：未识别到应用";
        }
        String label;
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            label = pkg;
        }
        int st = AppCfg.getAppState(this, pkg);
        boolean active = isActiveForCurrentApp();
        String tag;
        if (active) {
            tag = "生效";
        } else if (st == -1) {
            tag = "不生效(单独禁用)";
        } else if (st == 0) {
            tag = "不生效(跟随全局=关)";
        } else {
            tag = "不生效";
        }
        return "当前：" + label + " · " + tag;
    }

    
private Notification buildNotification() {
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        boolean vertical = (Cfg.axis(this) == Cfg.AXIS_VERTICAL);
        boolean enabled = Cfg.enabled(this);

        Intent toggleIntent = new Intent(this, RingForegroundService.class);
        toggleIntent.setAction(ACTION_TOGGLE_AXIS);
        PendingIntent togglePi = PendingIntent.getService(
                this, 100, toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent enableIntent = new Intent(this, RingForegroundService.class);
        enableIntent.setAction(ACTION_TOGGLE_ENABLED);
        PendingIntent enablePi = PendingIntent.getService(
                this, 101, enableIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        RemoteViews rv = new RemoteViews(getPackageName(), R.layout.notif_layout);
        rv.setTextViewText(R.id.btn_axis, vertical ? "切到左右" : "切到上下");
        rv.setTextViewText(R.id.btn_toggle, enabled ? "停用" : "启用");
        rv.setTextViewText(R.id.notif_state,
                enabled ? (vertical ? "上下" : "左右") : "已停用");
        rv.setTextColor(R.id.notif_state,
                enabled ? 0xFF22C55E : 0xFF94A3B8);

        // 诊断：显示当前识别到的前台 App 与判断结果
        rv.setTextViewText(R.id.notif_app, buildAppLine());
        rv.setOnClickPendingIntent(R.id.btn_axis, togglePi);
        rv.setOnClickPendingIntent(R.id.btn_toggle, enablePi);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }

        b.setSmallIcon(R.drawable.ic_notif)
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentTitle("变焦环控制")
                .setContentText(enabled ? (vertical ? "竖直滑动" : "水平滑动") : "已停用")
                .setCustomContentView(rv)
                .setCustomBigContentView(rv);

        return b.build();
    }
    private void registerSensor() {
        if (sm == null) {
            lastState = "SensorManager 不可用";
            return;
        }
        List<Sensor> all = sm.getSensorList(Sensor.TYPE_ALL);
        for (Sensor s : all) {
            String n = s.getName();
            if (n != null && n.toLowerCase().contains("optical")) {
                ringSensor = s;
                break;
            }
        }
        if (ringSensor == null) {
            for (Sensor s : all) {
                if (s.getType() == TYPE_OPTICAL_TRACKING) {
                    ringSensor = s;
                    break;
                }
            }
        }
        if (ringSensor == null) {
            lastState = "没找到变焦环传感器";
            Log.w(TAG, lastState);
            return;
        }
        boolean ok = sm.registerListener(this, ringSensor, SensorManager.SENSOR_DELAY_GAME);
        lastState = ok ? ("已连接 " + ringSensor.getName()) : "注册监听失败";
        // 初始化 root 滚轮用的屏幕坐标
        try {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            android.view.WindowManager wm = (android.view.WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                wm.getDefaultDisplay().getRealMetrics(dm);
            }
        } catch (Exception ignored) {
        }
        Log.i(TAG, lastState);
    }

    /**
     * 当前前台 App 是否应该响应变焦环。
     *
     * 三层优先级：
     *   1. 启用服务（running，外部已判断）
     *   2. 单 App 独立开关：打开即生效（强例外），关闭即不生效
     *   3. 未单独设置的 App，跟随全局开关
     */
    private boolean isActiveForCurrentApp() {
        // 方向盘页面自己订阅了传感器，服务必须让路，否则角度会被重复累加
        if (padPreview) {
            return false;
        }
        // 优先 UsageStats（不受输入法/悬浮窗干扰），失败退回无障碍
        String pkg = ForegroundApp.get(this);
        if (ForegroundApp.isIgnorable(pkg)) {
            return false;
        }
        // 自己这个 App 不响应，避免自己转自己
        if (pkg != null && pkg.equals(getPackageName())) {
            return false;
        }
        return AppCfg.shouldWork(this, Cfg.enabled(this), pkg);
    }

    // ==================== 按 App 覆盖的参数 ====================

    /** 当前前台包名，可能为 null */
    private String fgPkg() {
        String p = ForegroundApp.get(this);
        if (ForegroundApp.isIgnorable(p)) {
            return RingAccessibilityService.foregroundPkg;
        }
        return p;
    }

    private int pMode() {
        return AppCfg.intOf(this, fgPkg(), "mode", Cfg.mode(this));
    }

    private int pAxis() {
        return AppCfg.intOf(this, fgPkg(), "axis", Cfg.axis(this));
    }

    private float pSens() {
        return AppCfg.floatOf(this, fgPkg(), "sens", Cfg.sens(this));
    }

    private float pUnit() {
        return AppCfg.floatOf(this, fgPkg(), "unit", Cfg.wheelUnit(this));
    }

    private int pStep() {
        return AppCfg.intOf(this, fgPkg(), "step", Cfg.stepPx(this));
    }

    private int pFixedSide() {
        return AppCfg.intOf(this, fgPkg(), "fixed_side", Cfg.fixedDirSide(this));
    }

    private boolean pFixedDir() {
        return AppCfg.boolOf(this, fgPkg(), "fixed", Cfg.fixedDir(this));
    }

    private float pDead() {
        return AppCfg.floatOf(this, fgPkg(), "dead", Cfg.deadzone(this));
    }

    private int axis() {
        return pAxis() == Cfg.AXIS_HORIZONTAL
                ? RingAccessibilityService.DIR_HORIZONTAL
                : RingAccessibilityService.DIR_VERTICAL;
    }

    private long lastDiagMs = 0L;

    @Override
    public void onSensorChanged(SensorEvent e) {
        // 诊断：每 2 秒打一次判断链路，定位「显示生效但不动」的原因
        long nowDiag = SystemClock.elapsedRealtime();
        if (nowDiag - lastDiagMs > 2000L) {
            lastDiagMs = nowDiag;
            try {
                String dp = ForegroundApp.get(this);
                boolean ign = ForegroundApp.isIgnorable(dp);
                int dst = AppCfg.getAppState(this, dp);
                boolean gEn = Cfg.enabled(this);
                boolean res = isActiveForCurrentApp();
                Log.i(TAG, "DIAG pkg=" + dp
                        + " ignorable=" + ign
                        + " appState=" + dst
                        + " globalEnabled=" + gEn
                        + " active=" + res
                        + " hasPerm=" + ForegroundApp.hasUsagePermission(this)
                        + " running=" + running);
            } catch (Exception ex) {
                Log.i(TAG, "DIAG error " + ex);
            }
        }

        if (!running || !isActiveForCurrentApp()) {
            return;
        }
        // 预览模式跳过冷却：方向盘不需要节流
        if (SystemClock.elapsedRealtime() < cooldownUntilMs) {
            return;
        }
        float[] v = e.values;
        if (v == null || v.length < 3) {
            return;
        }
        float counter = v[2];

        if (Float.isNaN(lastCounter)) {
            lastCounter = counter;
            return;
        }

        float d = counter - lastCounter;
        lastCounter = counter;

        // ===== 方向盘：把转动变成摇杆角度，直接发出去 =====
        // 放在防抖/死区之前，赛车游戏要跟手，不能被滚动模式的
        // 方向确认帧拖慢；同时不受冷却时间影响。
        if (pMode() == Cfg.MODE_WHEEL_PAD) {
            if (Math.abs(d) < Cfg.padDead(this)) {
                return;
            }
            VirtualPad.ensureReady(this);
            // 计数 → 真实角度 → 摇杆，两步完全解耦：
            // countsPerTurn 负责"准不准"，turnsToFull 负责"灵不灵"
            float counts = Cfg.padCountsPerTurn(this);
            float fullDeg = Cfg.padTurnsToFull(this) * 360f;
            float deg = d / counts * 360f;
            boolean rev = Cfg.padReverse(this);
            VirtualPad.turn(this, rev ? -deg : deg, fullDeg);
            lastDataMs = SystemClock.elapsedRealtime();
            dbgDelta = d;
            return;
        }

        if (Math.abs(d) < pDead()) {
            return;
        }

        // ===== 方向防抖 =====
        // 目的：滤掉单帧噪声（幅度很小的翻号），但反转方向时要立刻响应，
        // 并且必须清掉反方向遗留的累积值，否则会出现「往回转还先按原方向走一下」。
        int curDir = d > 0 ? 1 : -1;
        if (curDir != lastDir) {
            // 方向真的变了：丢掉上一方向没发完的累积
            if (lastDir != 0) {
                pendingPx = 0f;
                wheelAccum = 0f;
            }
            lastDir = curDir;
            dirConfirm = 1;
            // 只有幅度够大才认为是真的反向，否则当噪声忽略
            if (Math.abs(d) < pDead() * 3f) {
                return;
            }
            // 确认是真实反向，直接放行，不等下一帧
            dirConfirm = DIR_CONFIRM_FRAMES;
            Log.i(TAG, "DIR 反向 cur=" + curDir + " d=" + d
                    + " pendingPx已清零 wheelAccum已清零");
        } else {
            dirConfirm++;
        }

        if (dirConfirm < DIR_CONFIRM_FRAMES) {
            return;
        }

        lastDataMs = SystemClock.elapsedRealtime();
        dbgDelta = d;

        boolean reverse = Cfg.reverse(this);
        float signedD = reverse ? -d : d;

        // 固定方向：不管正转反转，只按选定的方向输出
        if (pFixedDir()) {
            float mag = Math.abs(d);
            signedD = (pFixedSide() == Cfg.FIXED_NEGATIVE) ? -mag : mag;
        }

        {
            int mode0 = pMode();
            float na = wheelAccum + signedD;
            float unit = Math.max(10f, pUnit());
            float limit = unit * 6f;
            if (na > limit) {
                na = limit;
            } else if (na < -limit) {
                na = -limit;
            }
            wheelAccum = na;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    /**
     * 保活循环。
     *
     * 小米等 ROM 会在切后台时移除前台通知，进而判定服务非法。
     * 每秒调一次 startForeground，确保前台身份与通知始终存在，
     * 同时让通知里的「当前应用」保持最新。
     */
    private void startKeepAlive() {
        if (keepAliveThread != null) {
            return;
        }
        keepAliveThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!running) {
                        return;
                    }
                    try {
                        startForegroundCompat();
                    } catch (Exception ignored) {
                    }
                }
            }
        }, "ring-keepalive");
        keepAliveThread.setDaemon(true);
        keepAliveThread.start();
    }

    private void scheduleTick(long delayMs) {
        if (!running) {
            return;
        }
        if (tickScheduled.compareAndSet(false, true)) {
            handler.postDelayed(() -> {
                if (!running) {
                    tickScheduled.set(false);
                    return;
                }
                tickScheduled.set(false);
                long next = emitTick();
                scheduleTick(next);
            }, delayMs);
        }
    }

    private long emitTick() {
        if (!running) {
            return TICK_IDLE_MS;
        }

        long nowReal = SystemClock.elapsedRealtime();
        boolean idle = (nowReal - lastDataMs) > IDLE_THRESHOLD_MS;

        boolean paused = Cfg.isPaused(this);
        dbgPaused = paused;
        // 只判断暂停。全局开关是否生效交给 isActiveForCurrentApp() 统一裁决，
        // 否则单 App 单独启用的例外会被这里的全局开关直接否掉。
        if (paused) {
            pendingPx = 0f;
            wheelAccum = 0f;
            return TICK_IDLE_MS;
        }

        if (nowReal < cooldownUntilMs) {
            pendingPx = 0f;
            wheelAccum = 0f;
            return TICK_ACTIVE_MS;
        }

        RingAccessibilityService svc = RingAccessibilityService.get();
        if (svc == null) {
            lastState = "无障碍服务未开启";
            return TICK_IDLE_MS;
        }

        int mode = pMode();

        long now = System.currentTimeMillis();
        long minInterval = Math.max(30, Cfg.minIntervalMs(this));
        if (now - lastEmitMs < minInterval) {
            return idle ? TICK_IDLE_MS : TICK_ACTIVE_MS;
        }

        if (inflight.get() > MAX_INFLIGHT) {
            return TICK_ACTIVE_MS;
        }

        long dur = Math.max(40, Math.min(600, Cfg.gestureDurationMs(this)));

if (mode == Cfg.MODE_SMOOTH) {
            float p = pendingPx;
            float minEmit = 90f;
            if (Math.abs(p) < minEmit) {
                return idle ? TICK_IDLE_MS : TICK_ACTIVE_MS;
            }

            // 平滑窗口：如果最近还在转动，先等一会儿把位移攒起来，
            // 再一次性平滑滑出去，避免每次小转动单发一个手势。
            int win = Cfg.smoothWindowMs(this);
            if (win > 0) {
                long sinceData = nowReal - lastDataMs;
                if (sinceData < win) {
                    // 还在窗口内，继续攒
                    return TICK_ACTIVE_MS;
                }
            }

            float take = p;
            float maxPer = Cfg.maxSwipePx(this);
            if (take > maxPer) {
                take = maxPer;
            } else if (take < -maxPer) {
                take = -maxPer;
            }
            pendingPx = p - take;
            dbgPending = pendingPx;

            // 位移越大，手势时长相应拉长，滑动更平滑
            long slideDur = dur;
            float absTake = Math.abs(take);
            if (absTake > 120f) {
                slideDur = Math.min(400L, (long) (dur * (absTake / 120f)));
            }

            inflight.incrementAndGet();
            boolean ok = svc.swipe(take, slideDur, axis(), () -> inflight.decrementAndGet());
            if (!ok) {
                inflight.decrementAndGet();
                pendingPx += take;
            } else {
                lastEmitMs = now;
                applyCooldown();
            }
            return TICK_ACTIVE_MS;
        }
        float unit = Math.max(10f, pUnit());
        float acc = wheelAccum;
        if (Math.abs(acc) < unit) {
            return idle ? TICK_IDLE_MS : TICK_ACTIVE_MS;
        }
        boolean up = acc > 0;
        wheelAccum = up ? (acc - unit) : (acc + unit);

        int px = Math.max(90, pStep());

        inflight.incrementAndGet();
        boolean ok = svc.swipe(up ? px : -px, dur, axis(), () -> inflight.decrementAndGet());
        if (!ok) {
            inflight.decrementAndGet();
            wheelAccum += up ? unit : -unit;
        } else {
            lastEmitMs = now;
            applyCooldown();
        }
        return TICK_ACTIVE_MS;
    }

    private void applyCooldown() {
        // 每次成功触发手势后震一下
        doHaptic();
        int cd = Cfg.cooldownMs(this);
        if (cd > 0) {
            cooldownUntilMs = SystemClock.elapsedRealtime() + cd;
            pendingPx = 0f;
            wheelAccum = 0f;
        }
    }

    /**
     * 转动震动：每次触发震一次。
     * 强度和时长读用户设置；仅做一个极短的保护间隔（80ms），
     * 防止连续转动时马达过载，正常使用感知不到这个限制。
     */
    private void doHaptic() {
        if (!Cfg.haptic(this)) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastHapticMs < 80L) {
            return;
        }
        lastHapticMs = now;
        try {
            if (vibrator == null) {
                vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (vibrator == null || !vibrator.hasVibrator()) {
                return;
            }
            int ms = Math.max(5, Math.min(100, Cfg.hapticMs(this)));
            int amp = Math.max(10, Math.min(255, Cfg.hapticAmp(this)));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(ms, amp));
            } else {
                vibrator.vibrate(ms);
            }
        } catch (Exception ignored) {
        }
    }
    public static void start(Context c) {
        Intent i = new Intent(c, RingForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, RingForegroundService.class));
    }
}