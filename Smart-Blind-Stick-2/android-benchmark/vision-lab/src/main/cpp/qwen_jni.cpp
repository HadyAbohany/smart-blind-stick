#include "qwen_core.h"
#include <jni.h>
#include <stdexcept>
#include <vector>

namespace {
std::string string(JNIEnv *env, jstring value) {
    if (!value) throw std::runtime_error("Missing model path or prompt");
    const char *bytes = env->GetStringUTFChars(value, nullptr);
    if (!bytes) throw std::runtime_error("Could not read caption parameter");
    std::string result(bytes);
    env->ReleaseStringUTFChars(value, bytes);
    return result;
}
}
extern "C" JNIEXPORT jlong JNICALL Java_eg_sbs_benchmark_QwenNative_begin(JNIEnv *, jclass) {
    return sbs::new_request();
}
extern "C" JNIEXPORT void JNICALL Java_eg_sbs_benchmark_QwenNative_cancel(JNIEnv *, jclass) {
    sbs::cancel();
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_eg_sbs_benchmark_QwenNative_describe(
        JNIEnv *env, jclass, jlong request, jstring model, jstring projector,
        jbyteArray bytes, jint width, jint height, jstring prompt) {
    try {
        if (!bytes || width < 1 || height < 1 || width > 1024 || height > 1024
                || env->GetArrayLength(bytes) != static_cast<jlong>(width) * height * 3)
            throw std::runtime_error("Invalid image dimensions");
        std::vector<uint8_t> rgb(env->GetArrayLength(bytes));
        env->GetByteArrayRegion(bytes, 0, rgb.size(), reinterpret_cast<jbyte *>(rgb.data()));
        auto text = sbs::caption(request, string(env, model), string(env, projector),
                                 rgb.data(), width, height, string(env, prompt));
        jbyteArray output = env->NewByteArray(text.size());
        if (output) env->SetByteArrayRegion(output, 0, text.size(), reinterpret_cast<const jbyte *>(text.data()));
        return output;
    } catch (const std::exception &error) {
        env->ThrowNew(env->FindClass("java/io/IOException"), error.what());
        return nullptr;
    }
}
