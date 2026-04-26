#include <jni.h>
#include "vulkan_preprocessor.h"
#include <android/log.h>

#define LOG_TAG "VulkanJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static VulkanPreprocessor* preprocessor = nullptr;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_detector_esp_preprocess_VulkanPreprocessor_nativeInit(
        JNIEnv* env, jobject thiz,
        jint srcWidth, jint srcHeight, jint dstSize) {

    if (preprocessor) {
        preprocessor->cleanup();
        delete preprocessor;
    }

    preprocessor = new VulkanPreprocessor();
    bool ok = preprocessor->init((uint32_t)srcWidth, (uint32_t)srcHeight, (uint32_t)dstSize);

    if (!ok) {
        LOGE("Vulkan 初始化失败，将回退到 CPU");
        delete preprocessor;
        preprocessor = nullptr;
        return JNI_FALSE;
    }

    LOGI("Vulkan JNI 初始化成功");
    return JNI_TRUE;
}

JNIEXPORT jfloat JNICALL
Java_com_detector_esp_preprocess_VulkanPreprocessor_nativeProcess(
        JNIEnv* env, jobject thiz,
        jbyteArray yArray, jbyteArray uvArray,
        jint yRowStride, jint uvRowStride,
        jbyteArray outputArray) {

    if (!preprocessor || !preprocessor->isAvailable()) return -1.0f;

    jbyte* yData = env->GetByteArrayElements(yArray, nullptr);
    jbyte* uvData = env->GetByteArrayElements(uvArray, nullptr);
    jbyte* outData = env->GetByteArrayElements(outputArray, nullptr);

    jint ySize = env->GetArrayLength(yArray);
    jint uvSize = env->GetArrayLength(uvArray);

    float ms = preprocessor->process(
            (const uint8_t*)yData, (uint32_t)ySize,
            (const uint8_t*)uvData, (uint32_t)uvSize,
            (uint32_t)yRowStride, (uint32_t)uvRowStride,
            (uint8_t*)outData
    );

    env->ReleaseByteArrayElements(yArray, yData, JNI_ABORT);
    env->ReleaseByteArrayElements(uvArray, uvData, JNI_ABORT);
    env->ReleaseByteArrayElements(outputArray, outData, 0);  // 0 = 写回

    return ms;
}

JNIEXPORT void JNICALL
Java_com_detector_esp_preprocess_VulkanPreprocessor_nativeCleanup(
        JNIEnv* env, jobject thiz) {
    if (preprocessor) {
        preprocessor->cleanup();
        delete preprocessor;
        preprocessor = nullptr;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_detector_esp_preprocess_VulkanPreprocessor_nativeIsAvailable(
        JNIEnv* env, jobject thiz) {
    return (preprocessor && preprocessor->isAvailable()) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
