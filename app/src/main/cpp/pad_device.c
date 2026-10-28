/*
 * pad_device.c —— RingCtl 虚拟手柄（uinput 常驻）
 *
 * 在 system_server 进程内直接持有 /dev/uinput 的 fd，
 * 创建一次虚拟手柄后常驻，后续只写 ABS_X 事件即可，
 * 延迟 ≈ 单次 write syscall（< 1ms），不再需要每次重建。
 *
 * 关键点：
 *  - 必须用 UI_DEV_SETUP 传结构体（纯 Java 做不到，所以用 native）
 *  - 不设置 INPUT_PROP_POINTER（手柄不是指针设备）
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

static int pad_create(const char *name) {
    if (g_fd >= 0) {
        LOGI("device already created, fd=%d", g_fd);
        return 0;
    }

    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        LOGE("open /dev/uinput failed: %d (%s)", errno, strerror(errno));
        return -errno;
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
    ioctl(fd, UI_SET_KEYBIT, BTN_DPAD_UP);
    ioctl(fd, UI_SET_KEYBIT, BTN_DPAD_DOWN);
    ioctl(fd, UI_SET_KEYBIT, BTN_DPAD_LEFT);
    ioctl(fd, UI_SET_KEYBIT, BTN_DPAD_RIGHT);

    ioctl(fd, UI_SET_ABSBIT, ABS_X);
    ioctl(fd, UI_SET_ABSBIT, ABS_Y);
    ioctl(fd, UI_SET_ABSBIT, ABS_RX);
    ioctl(fd, UI_SET_ABSBIT, ABS_RY);
    ioctl(fd, UI_SET_ABSBIT, ABS_Z);
    ioctl(fd, UI_SET_ABSBIT, ABS_RZ);

    struct uinput_setup usetup;
    memset(&usetup, 0, sizeof(usetup));
    usetup.id.bustype = BUS_VIRTUAL;
    usetup.id.vendor = 0x1234;
    usetup.id.product = 0x5678;
    usetup.id.version = 1;
    strncpy(usetup.name, name, UINPUT_MAX_NAME_SIZE - 1);
    usetup.name[UINPUT_MAX_NAME_SIZE - 1] = '\0';
    usetup.ff_effects_max = 0;

    if (ioctl(fd, UI_DEV_SETUP, &usetup) < 0) {
        LOGE("UI_DEV_SETUP failed: %d (%s)", errno, strerror(errno));
        close(fd);
        return -errno;
    }

    struct uinput_abs_setup abs_setup;
    int abs_codes[6] = { ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_Z, ABS_RZ };
    for (int i = 0; i < 6; i++) {
        memset(&abs_setup, 0, sizeof(abs_setup));
        abs_setup.code = (__u16) abs_codes[i];
        abs_setup.absinfo.minimum = -32767;
        abs_setup.absinfo.maximum = 32767;
        abs_setup.absinfo.fuzz = 0;
        abs_setup.absinfo.flat = 0;
        abs_setup.absinfo.resolution = 0;
        if (ioctl(fd, UI_ABS_SETUP, &abs_setup) < 0) {
            LOGE("UI_ABS_SETUP(%d) failed: %d", abs_codes[i], errno);
        }
    }

    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        LOGE("UI_DEV_CREATE failed: %d (%s)", errno, strerror(errno));
        close(fd);
        return -errno;
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

JNIEXPORT jint JNICALL
Java_com_breeze_ringctl_PadDeviceNative_nativeCreate(JNIEnv *env, jobject thiz, jstring jname) {
    const char *name = "RingCtlPad";
    if (jname != NULL) {
        const char *tmp = (*env)->GetStringUTFChars(env, jname, NULL);
        if (tmp != NULL) {
            name = tmp;
        }
    }
    int r = pad_create(name);
    if (jname != NULL && name != NULL) {
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