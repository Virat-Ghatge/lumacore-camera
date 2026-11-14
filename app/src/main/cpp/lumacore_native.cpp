#include <jni.h>
#include <android/log.h>
#include <string>

#define LOG_TAG "LumaCoreNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace lumacore {

constexpr const char* VERSION_STRING = "1.0.0-milestone1";

class NativeEngine {
public:
    static NativeEngine& getInstance() {
        static NativeEngine instance;
        return instance;
    }

    bool initialize() {
        LOGI("LumaCore NativeEngine initializing with C++20 runtime...");
        m_initialized = true;
        return true;
    }

    [[nodiscard]] bool isInitialized() const {
        return m_initialized;
    }

    [[nodiscard]] const char* getVersion() const {
        return VERSION_STRING;
    }

private:
    NativeEngine() = default;
    ~NativeEngine() = default;
    NativeEngine(const NativeEngine&) = delete;
    NativeEngine& operator=(const NativeEngine&) = delete;

    bool m_initialized{false};
};

} // namespace lumacore

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    if (vm == nullptr) {
        return JNI_ERR;
    }
    LOGI("JNI_OnLoad: LumaCore native library loaded successfully.");
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_lumacore_camera_core_nativebridge_NativePipeline_nativeInit(
    JNIEnv* env,
    jobject /* thiz */
) {
    (void)env;
    return static_cast<jboolean>(lumacore::NativeEngine::getInstance().initialize());
}

JNIEXPORT jstring JNICALL
Java_com_lumacore_camera_core_nativebridge_NativePipeline_nativeGetVersion(
    JNIEnv* env,
    jobject /* thiz */
) {
    if (env == nullptr) {
        return nullptr;
    }
    const char* version = lumacore::NativeEngine::getInstance().getVersion();
    return env->NewStringUTF(version);
}

} // extern "C"
