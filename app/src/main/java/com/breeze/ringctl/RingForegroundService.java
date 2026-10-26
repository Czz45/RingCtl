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

    public static final int TYPE_OPTICAL_TRACKING = 33171136;

    private static final long TICK_ACTIVE_MS = 16;
    private static final long TICK_IDLE_MS = 60;
    private static final long IDLE_THRESHOLD_MS = 500;
    private static final int MAX_INFLIGHT = 2;

    public static volatile boolean running = false;
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

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        startForeground(NOTI_ID, buildNotification());

        emitThread = new HandlerThread("ring-emit");
        emitThread.start();
        handler = new Handler(emitThread.getLooper());

        registerSensor();
        running = true;
        scheduleTick(0);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            RingAccessibilityService s = RingAccessibilityService.get();
            if (s != null) {
                s.dragStop();
            }
        } catch (Exception ignored) {
        }
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

    private Notification buildNotification() {
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("变焦环控制运行中")
                .setContentText("转动环即可滚动")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
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
                RootWheel.setScreenSize(dm.widthPixels, dm.heightPixels);
            }
        } catch (Exception ignored) {
        }
        Log.i(TAG, lastState);
    }

    private int axis() {
        return Cfg.axis(this) == Cfg.AXIS_HORIZONTAL
                ? RingAccessibilityService.DIR_HORIZONTAL
                : RingAccessibilityService.DIR_VERTICAL;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (!running || !Cfg.enabled(this)) {
            return;
        }
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

        if (Math.abs(d) < Cfg.deadzone(this)) {
            return;
        }

        // ===== 方向防抖 =====
        // 来回转时单帧差值可能因噪声/惯性残留翻号，导致反向误触发。
        // 规则：连续 N 帧方向一致才认，否则丢弃这一帧。
        int curDir = d > 0 ? 1 : -1;
        if (curDir == lastDir) {
            dirConfirm++;
        } else {
            dirConfirm = 1;
            lastDir = curDir;
        }
        if (dirConfirm < DIR_CONFIRM_FRAMES) {
            return;
        }

        lastDataMs = SystemClock.elapsedRealtime();
        dbgDelta = d;

        boolean reverse = Cfg.reverse(this);
        float signedD = reverse ? -d : d;

        int mode = Cfg.mode(this);
        if (mode == Cfg.MODE_ROOT_WHEEL) {
            float na = wheelAccum + signedD;
            float unit = Math.max(10f, Cfg.wheelUnit(this));
            float limit = unit * 4f;
            if (na > limit) {
                na = limit;
            } else if (na < -limit) {
                na = -limit;
            }
            wheelAccum = na;
        } else if (mode == Cfg.MODE_SMOOTH || mode == Cfg.MODE_DRAG) {
            float px = signedD * Cfg.sens(this) * 0.5f;
            float np = pendingPx + px;
            float totalCap = Cfg.maxSwipePx(this) * 4f;
            if (np > totalCap) {
                np = totalCap;
            } else if (np < -totalCap) {
                np = -totalCap;
            }
            pendingPx = np;
            dbgPending = np;
        } else {
            float na = wheelAccum + signedD;
            float unit = Math.max(10f, Cfg.wheelUnit(this));
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
        if (paused || !Cfg.enabled(this)) {
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

        long now = System.currentTimeMillis();
        long minInterval = Math.max(30, Cfg.minIntervalMs(this));
        if (now - lastEmitMs < minInterval) {
            return idle ? TICK_IDLE_MS : TICK_ACTIVE_MS;
        }

        if (inflight.get() > MAX_INFLIGHT) {
            return TICK_ACTIVE_MS;
        }

        int mode = Cfg.mode(this);
        long dur = Math.max(40, Math.min(600, Cfg.gestureDurationMs(this)));

if (mode == Cfg.MODE_ROOT_WHEEL) {
            float acc = wheelAccum;
            float unit = Math.max(10f, Cfg.wheelUnit(this));
            if (Math.abs(acc) < unit) {
                return idle ? TICK_IDLE_MS : TICK_ACTIVE_MS;
            }
            if (now - lastRootWheelMs < 100L) {
                return TICK_ACTIVE_MS;
            }

            int units = (int) (acc / unit);
            if (units == 0) {
                units = acc > 0 ? 1 : -1;
            }
            wheelAccum = acc - units * unit;
            float cap = unit * 4f;
            if (wheelAccum > cap) {
                wheelAccum = cap;
            } else if (wheelAccum < -cap) {
                wheelAccum = -cap;
            }

            int steps = units * 3;
            boolean ok = RootWheel.roll(steps);
            if (ok) {
                lastRootWheelMs = now;
                lastEmitMs = now;
            } else {
                wheelAccum += units * unit;
            }
            return TICK_ACTIVE_MS;
        }
// ===== 连续拖拽模式 =====
        // 手指保持按下，位移持续喂进去，页面连续跟随；停止转动后自动抬手
        if (mode == Cfg.MODE_DRAG) {
            boolean horizontal = (Cfg.axis(this) == Cfg.AXIS_HORIZONTAL);
            float p = pendingPx;

            // 有位移就喂，没位移就让 DragSession 自己超时抬手
            if (Math.abs(p) >= 1f) {
                pendingPx = 0f;
                dbgPending = 0f;
                svc.dragFeed(p, horizontal);
                lastEmitMs = now;
            }
            return TICK_ACTIVE_MS;
        }

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
        float unit = Math.max(10f, Cfg.wheelUnit(this));
        float acc = wheelAccum;
        if (Math.abs(acc) < unit) {
            return idle ? TICK_IDLE_MS : TICK_ACTIVE_MS;
        }
        boolean up = acc > 0;
        wheelAccum = up ? (acc - unit) : (acc + unit);

        int px = Math.max(90, Cfg.stepPx(this));

        inflight.incrementAndGet();
        boolean ok;
        if (mode == Cfg.MODE_WHEEL) {
            ok = svc.swipe(up ? px : -px, dur, axis(), () -> inflight.decrementAndGet());
        } else {
            ok = svc.pageScroll(!up, () -> inflight.decrementAndGet());
        }
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