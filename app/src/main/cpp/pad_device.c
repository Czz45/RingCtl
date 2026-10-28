/*
 * pad_device.c —— RingCtl 虚拟手柄（uinput 常驻）
 *
 * 关键设计：
 *   App 进程是普通用户（u0_axxx），没有 /dev/uinput 的权限。
 *   但 App 有 root，可以先 `su -c chmod` 或用 root 打开设备，
 *   然后把 fd 通过 JNI 传进来，native 层用这个 fd 完成后续所有操作。
 *
 *   一旦 fd 到手，写事件就是普通 write，不需要再 su，延迟 < 1ms。
 */

#include <jni.h>
#include <android/log.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <linux/uinput.h>
#include <linux/input.h>

#define TAG "RingCtlPad"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static int g_fd = -1;
static int g_last_x = -999999;
static int g_last_y = -999999;

static int write_event(int fd, int type, int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = (__u16) type;
    ev.code = (__u16) code;
    ev.value = value;
    if (write(fd, &ev, sizeof(ev)) < 0) {
        return -errno;
    }
    return 0;
}

static void emit_syn(int fd) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = EV_SYN;
    ev.code = SYN_REPORT;
    ev.value = 0;
    write(fd, &ev, sizeof(ev));
}

/*
 * 用指定的 fd 初始化虚拟手柄。
 * fd 由 Java 层通过 root 提前打开并传进来。
 * 返回 0 成功，负值失败。
 */
static int pad_init_with_fd(int fd, const char *name) {
    if (fd < 0) {
        LOGE("invalid fd: %d", fd);
        return -EBADF;
    }
    if (g_fd >= 0) {
        LOGI("device already created, fd=%d", g_fd);
        return 0;
    }

    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_ABS);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);

    ioctl(fd, UI_SET_KEYBIT, BTN_A);
    ioctl(fd, UI_SET_KEYBIT, BTN_B);
    ioctl(fd, UI_SET_KEYBIT, BTN_X);
    ioctl(fd, UI_SET_KEYBIT, BTN_Y);
    ioctl(fd, UI_SET_KEYBIT, BTN_TL);
    ioctl(fd, UI_SET_KEYBIT, BTN_TR);
    ioctl(fd, UI_SET_KEYBIT, BTN_TL2);
    ioctl(fd, UI_SET_KEYBIT, BTN_TR2);
    ioctl(fd, UI_SET_KEYBIT, BTN_SELECT);
    ioctl(fd, UI_SET_KEYBIT, BTN_START);
    ioctl(fd, UI_SET_KEYBIT, BTN_THUMBL);
    ioctl(fd, UI_SET_KEYBIT, BTN_THUMBR);
    /* 十字键用 HAT 轴，不需要 BTN_DPAD_* */
    ioctl(fd, UI_SET_KEYBIT, BTN_MODE);   /* 居中 X 键 → BUTTON_MODE */

    /*
     * 按 Vendor_045e_Product_028e.kl 的要求声明轴：
     *   ABS_X (0x00) 左摇杆横
     *   ABS_Y (0x01) 左摇杆纵
     *   ABS_Z (0x02) 左扳机
     *   ABS_RX(0x03) 在 keylayout 里写作 Z → 右摇杆横
     *   ABS_RY(0x04) 在 keylayout 里写作 RZ → 右摇杆纵
     *   ABS_RZ(0x05) 右扳机
     * 十字键用 ABS_HAT0X / ABS_HAT0Y
     */
    ioctl(fd, UI_SET_ABSBIT, ABS_X);
    ioctl(fd, UI_SET_ABSBIT, ABS_Y);
    ioctl(fd, UI_SET_ABSBIT, ABS_Z);
    ioctl(fd, UI_SET_ABSBIT, ABS_RX);
    ioctl(fd, UI_SET_ABSBIT, ABS_RY);
    ioctl(fd, UI_SET_ABSBIT, ABS_RZ);
    ioctl(fd, UI_SET_ABSBIT, ABS_HAT0X);
    ioctl(fd, UI_SET_ABSBIT, ABS_HAT0Y);

    struct uinput_setup usetup;
    memset(&usetup, 0, sizeof(usetup));
    /*
     * 关键：用 Xbox 360 有线手柄的真实 VID/PID。
     * 安卓靠这对 ID 去找 /system/usr/keylayout/Vendor_045e_Product_028e.kl，
     * 有 keylayout 才知道这是手柄、轴值怎么映射。
     * 之前用虚构的 0x1234/0x5678，系统没有对应 keylayout，
     * 所以轴值传进去也不认。
     */
    usetup.id.bustype = BUS_USB;
    usetup.id.vendor = 0x045e;   /* Microsoft */
    usetup.id.product = 0x028e;  /* Xbox 360 Wired Controller */
    usetup.id.version = 0x0110;
    strncpy(usetup.name, name, UINPUT_MAX_NAME_SIZE - 1);
    usetup.name[UINPUT_MAX_NAME_SIZE - 1] = '\0';
    usetup.ff_effects_max = 0;

    if (ioctl(fd, UI_DEV_SETUP, &usetup) < 0) {
        int e = errno;
        LOGE("UI_DEV_SETUP failed: %d (%s)", e, strerror(e));
        return -e;
    }

    /* 摇杆轴：-32767 ~ 32767，flat=4096 与官方 keylayout 一致 */
    struct uinput_abs_setup abs_setup;
    int abs_codes[6] = { ABS_X, ABS_Y, ABS_Z, ABS_RX, ABS_RY, ABS_RZ };
    for (int i = 0; i < 6; i++) {
        memset(&abs_setup, 0, sizeof(abs_setup));
        abs_setup.code = (__u16) abs_codes[i];
        abs_setup.absinfo.minimum = -32767;
        abs_setup.absinfo.maximum = 32767;
        abs_setup.absinfo.fuzz = 0;
        abs_setup.absinfo.flat = i < 4 ? 4096 : 0;
        abs_setup.absinfo.resolution = 0;
        if (ioctl(fd, UI_ABS_SETUP, &abs_setup) < 0) {
            LOGE("UI_ABS_SETUP(%d) failed: %d", abs_codes[i], errno);
        }
    }

    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        int e = errno;
        LOGE("UI_DEV_CREATE failed: %d (%s)", e, strerror(e));
        return -e;
    }

    g_fd = fd;
    g_last_x = -999999;
    g_last_y = -999999;
    LOGI("virtual gamepad created: %s fd=%d", name, fd);

    usleep(120 * 1000);
    return 0;
}

static int pad_move(int x, int y) {
    if (g_fd < 0) {
        LOGE("move but device not created");
        return -1;
    }
    if (x > 32767) x = 32767;
    if (x < -32767) x = -32767;
    if (y > 32767) y = 32767;
    if (y < -32767) y = -32767;

    if (x != g_last_x) {
        int r = write_event(g_fd, EV_ABS, ABS_X, x);
        if (r < 0) { LOGE("write ABS_X failed: %d", r); return r; }
        g_last_x = x;
    }
    if (y != g_last_y) {
        int r = write_event(g_fd, EV_ABS, ABS_Y, y);
        if (r < 0) { LOGE("write ABS_Y failed: %d", r); return r; }
        g_last_y = y;
    }
    emit_syn(g_fd);
    return 0;
}

static void pad_destroy(void) {
    if (g_fd < 0) return;
    ioctl(g_fd, UI_DEV_DESTROY);
    close(g_fd);
    g_fd = -1;
    g_last_x = -999999;
    g_last_y = -999999;
    LOGI("virtual gamepad destroyed");
}

/* ==================== JNI ==================== */

/*
 * 新增：用外部传入的 fd 创建设备。
 * Java 层通过 root 打开 /dev/uinput 得到 fd，
 * 用 ParcelFileDescriptor.detachFd() 取出原始 fd 传进来。
 */
JNIEXPORT jint JNICALL
Java_com_breeze_ringctl_PadDeviceNative_nativeInitWithFd(JNIEnv *env, jobject thiz, jint fd, jstring jname) {
    const char *name = "RingCtlPad";
    if (jname != NULL) {
        const char *tmp = (*env)->GetStringUTFChars(env, jname, NULL);
        if (tmp != NULL) name = tmp;
    }
    int r = pad_init_with_fd((int) fd, name);
    if (jname != NULL) {
        (*env)->ReleaseStringUTFChars(env, jname, name);
    }
    return r;
}

JNIEXPORT jint JNICALL
Java_com_breeze_ringctl_PadDeviceNative_nativeMove(JNIEnv *env, jobject thiz, jint x, jint y) {
    return pad_move(x, y);
}

JNIEXPORT void JNICALL
Java_com_breeze_ringctl_PadDeviceNative_nativeDestroy(JNIEnv *env, jobject thiz) {
    pad_destroy();
}

JNIEXPORT jboolean JNICALL
Java_com_breeze_ringctl_PadDeviceNative_nativeIsReady(JNIEnv *env, jobject thiz) {
    return g_fd >= 0 ? JNI_TRUE : JNI_FALSE;
}

/*
 * 本进程自己打开 /dev/uinput 并初始化。
 * 前提：已用 root 把设备权限改成 666。
 * fd 归本进程所有，后续写事件不需要任何权限检查。
 */
JNIEXPORT jint JNICALL
Java_com_breeze_ringctl_PadDeviceNative_nativeOpenAndInit(JNIEnv *env, jobject thiz, jstring jname) {
    if (g_fd >= 0) return 0;

    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        int e = errno;
        LOGE("open /dev/uinput failed: %d (%s)", e, strerror(e));
        return -e;
    }

    const char *name = "RingCtlPad";
    if (jname != NULL) {
        const char *tmp = (*env)->GetStringUTFChars(env, jname, NULL);
        if (tmp != NULL) name = tmp;
    }
    int r = pad_init_with_fd(fd, name);
    if (jname != NULL) {
        (*env)->ReleaseStringUTFChars(env, jname, name);
    }
    if (r != 0) {
        close(fd);
    }
    return r;
}

/* 探测当前进程能否直接打开 /dev/uinput（普通 App 通常为 false） */
JNIEXPORT jboolean JNICALL
Java_com_breeze_ringctl_PadDeviceNative_nativeCanOpen(JNIEnv *env, jobject thiz) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        LOGE("canOpen false: %d (%s)", errno, strerror(errno));
        return JNI_FALSE;
    }
    close(fd);
    return JNI_TRUE;
}