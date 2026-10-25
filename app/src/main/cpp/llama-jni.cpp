// llama-jni.cpp —— llama.cpp 的极简 Android JNI 封装（bloom-hub mobile）
// 只做三件事：加载 GGUF、同步生成（可中断）、释放。
// 提示词（含 ChatML 模板）由 Kotlin 侧拼好整串传入，native 不做模板处理。
// 生成循环采用 llama.cpp 官方 simple 示例的模式：先整段喂 prompt，之后逐 token。
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "pocket-llm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Handle {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *sampler = nullptr;
    const llama_vocab *vocab = nullptr; // 新版 API：tokenizer 系列都传 vocab
    std::atomic<bool> stop{false};
};

std::string toUtf8(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_bloomhub_pocket_llm_LlamaBridge_nativeLoad(
        JNIEnv *env, jobject, jstring jPath, jint nCtx, jint nThreads, jfloat temp) {
    const std::string path = toUtf8(env, jPath);
    if (path.empty()) return 0;

    llama_model_params mp = llama_model_default_params();
    auto *h = new Handle();
    h->model = llama_model_load_from_file(path.c_str(), mp);
    if (!h->model) { LOGE("load model failed: %s", path.c_str()); delete h; return 0; }
    h->vocab = llama_model_get_vocab(h->model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) nCtx;
    cp.n_threads = (uint32_t) nThreads;
    cp.n_threads_batch = (uint32_t) nThreads;
    h->ctx = llama_init_from_model(h->model, cp);
    if (!h->ctx) { LOGE("init ctx failed"); llama_model_free(h->model); delete h; return 0; }

    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    h->sampler = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(h->sampler, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(h->sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(h->sampler, llama_sampler_init_dist(0));

    LOGI("model loaded: %s (ctx=%d threads=%d)", path.c_str(), (int) nCtx, (int) nThreads);
    return (jlong) (intptr_t) h;
}

JNIEXPORT void JNICALL
Java_com_bloomhub_pocket_llm_LlamaBridge_nativeStop(JNIEnv *, jobject, jlong jHandle) {
    if (jHandle == 0) return;
    ((Handle *) (intptr_t) jHandle)->stop.store(true);
}

// 同步生成：prompt 已含完整 ChatML；返回生成的文本（不含 prompt）。
JNIEXPORT jstring JNICALL
Java_com_bloomhub_pocket_llm_LlamaBridge_nativeGenerate(
        JNIEnv *env, jobject, jlong jHandle, jstring jPrompt, jint maxTokens) {
    if (jHandle == 0) return env->NewStringUTF("");
    auto *h = (Handle *) (intptr_t) jHandle;
    const std::string prompt = toUtf8(env, jPrompt);
    h->stop.store(false);

    std::vector<llama_token> tokens(prompt.size() / 2 + 16);
    int n = llama_tokenize(h->vocab, prompt.c_str(), prompt.size(), tokens.data(),
                           (int) tokens.size(), false, true);
    if (n < 0) {
        tokens.resize((size_t) (-n));
        n = llama_tokenize(h->vocab, prompt.c_str(), prompt.size(), tokens.data(),
                           (int) tokens.size(), false, true);
        if (n < 0) { LOGE("tokenize failed"); return env->NewStringUTF(""); }
    }
    tokens.resize((size_t) n);

    std::string out;
    std::vector<char> piece(64);

    // 官方 simple 模式：先整段 prompt，再逐 token
    llama_token new_token = LLAMA_TOKEN_NULL;
    int fed = 0;
    for (int i = 0; i < maxTokens; i++) {
        if (h->stop.load()) break;
        // 上下文余量检查（prompt + 已生成）
        if (i == 0) {
            if ((int) llama_n_ctx(h->ctx) < n + maxTokens + 4) {
                maxTokens = (int) llama_n_ctx(h->ctx) - n - 4;
                if (maxTokens <= 0) { LOGE("ctx too small for prompt"); break; }
            }
        }

        llama_batch batch = (i == 0)
                ? llama_batch_get_one(tokens.data(), (int) tokens.size())
                : llama_batch_get_one(&new_token, 1);
        fed += (int) batch.n_tokens;
        if (llama_decode(h->ctx, batch) != 0) { LOGE("decode failed"); break; }

        new_token = llama_sampler_sample(h->sampler, h->ctx, -1);
        if (llama_vocab_is_eog(h->vocab, new_token)) break;

        int len = llama_token_to_piece(h->vocab, new_token, piece.data(), (int) piece.size(), 0, true);
        if (len > 0) out.append(piece.data(), (size_t) len);
    }
    LOGI("generated %d chars (fed %d tokens)", (int) out.size(), fed);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_bloomhub_pocket_llm_LlamaBridge_nativeFree(JNIEnv *, jobject, jlong jHandle) {
    if (jHandle == 0) return;
    auto *h = (Handle *) (intptr_t) jHandle;
    if (h->sampler) llama_sampler_free(h->sampler);
    if (h->ctx) llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}

} // extern "C"
