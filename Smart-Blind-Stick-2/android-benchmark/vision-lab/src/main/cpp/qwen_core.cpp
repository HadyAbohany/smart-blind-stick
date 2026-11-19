#include "qwen_core.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <vector>

namespace sbs {
namespace {
std::atomic<int64_t> generation{0};
std::mutex inference_mutex;
std::once_flag backend_once;
struct Request {
    int64_t id;
    std::chrono::steady_clock::time_point deadline;
    bool aborted() const { return generation.load() != id || std::chrono::steady_clock::now() > deadline; }
};
bool abort_callback(void *data) { return static_cast<Request *>(data)->aborted(); }
bool progress_callback(float, void *data) { return !static_cast<Request *>(data)->aborted(); }
void check(const Request &request) {
    if (request.aborted()) throw std::runtime_error("Caption canceled or timed out");
}
template<class T, void (*Free)(T *)> using Owner = std::unique_ptr<T, decltype(Free)>;
}

int64_t new_request() { return ++generation; }
void cancel() { ++generation; }

std::string caption(int64_t id, const std::string &model_path, const std::string &projector_path,
                    const uint8_t *rgb, int width, int height, const std::string &prompt, int max_tokens) {
    Request request{id, std::chrono::steady_clock::now() + std::chrono::seconds(180)};
    check(request);
    if (!rgb || width < 1 || height < 1 || width > 1024 || height > 1024 || prompt.empty()
            || prompt.size() > 4096 || max_tokens < 1 || max_tokens > 192)
        throw std::runtime_error("Invalid caption input");
    std::unique_lock<std::mutex> lock(inference_mutex, std::try_to_lock);
    if (!lock.owns_lock()) throw std::runtime_error("Caption engine is busy");
    std::call_once(backend_once, [] { llama_backend_init(); });
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    mp.progress_callback = progress_callback;
    mp.progress_callback_user_data = &request;
    Owner<llama_model, llama_model_free> model(llama_model_load_from_file(model_path.c_str(), mp), llama_model_free);
    if (!model) throw std::runtime_error("Could not load Qwen language model");
    check(request);
    auto cp = llama_context_default_params();
    cp.n_ctx = 2048;
    cp.n_batch = 256;
    cp.n_ubatch = 128;
    cp.n_threads = 2;
    cp.n_threads_batch = 2;
    cp.abort_callback = abort_callback;
    cp.abort_callback_data = &request;
    Owner<llama_context, llama_free> context(llama_init_from_model(model.get(), cp), llama_free);
    if (!context) throw std::runtime_error("Could not allocate Qwen context");
    auto vp = mtmd_context_params_default();
    vp.use_gpu = false;
    vp.n_threads = 2;
    vp.warmup = false;
    vp.image_min_tokens = 64;
    vp.image_max_tokens = 256;
    vp.progress_callback = progress_callback;
    vp.progress_callback_user_data = &request;
    vp.cb_eval = [](ggml_tensor *, bool ask, void *data) {
        return ask || !static_cast<Request *>(data)->aborted();
    };
    vp.cb_eval_user_data = &request;
    Owner<mtmd_context, mtmd_free> vision(mtmd_init_from_file(projector_path.c_str(), model.get(), vp), mtmd_free);
    if (!vision || !mtmd_support_vision(vision.get())) throw std::runtime_error("Could not load Qwen vision encoder");
    check(request);
    Owner<mtmd_bitmap, mtmd_bitmap_free> bitmap(mtmd_bitmap_init(width, height, rgb), mtmd_bitmap_free);
    if (!bitmap) throw std::runtime_error("Could not prepare image");
    std::string user = std::string(mtmd_default_marker()) + "\n" + prompt;
    llama_chat_message message{"user", user.c_str()};
    std::vector<char> formatted(16384);
    int length = llama_chat_apply_template("chatml", &message, 1, true, formatted.data(), formatted.size());
    if (length < 0 || length >= static_cast<int>(formatted.size())) throw std::runtime_error("Invalid chat prompt");
    mtmd_input_text input{formatted.data(), static_cast<size_t>(length), true, true};
    Owner<mtmd_input_chunks, mtmd_input_chunks_free> chunks(mtmd_input_chunks_init(), mtmd_input_chunks_free);
    const mtmd_bitmap *images[] = {bitmap.get()};
    if (mtmd_tokenize(vision.get(), chunks.get(), &input, images, 1) != 0)
        throw std::runtime_error("Could not tokenize image and prompt");
    if (mtmd_helper_get_n_tokens(chunks.get()) + max_tokens >= 2048)
        throw std::runtime_error("Image exceeds caption context budget");
    llama_pos past = 0;
    if (mtmd_helper_eval_chunks(vision.get(), context.get(), chunks.get(), 0, 0, 256, true, &past) != 0)
        throw std::runtime_error("Could not encode caption input");
    check(request);
    Owner<llama_sampler, llama_sampler_free> sampler(llama_sampler_init_greedy(), llama_sampler_free);
    const auto *vocab = llama_model_get_vocab(model.get());
    std::string result;
    for (int i = 0; i < max_tokens; i++) {
        check(request);
        llama_token token = llama_sampler_sample(sampler.get(), context.get(), -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        std::vector<char> piece(256);
        int count = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
        if (count < 0) {
            piece.resize(-count);
            count = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
        }
        if (count > 0) result.append(piece.data(), count);
        if (result.size() > 6000) break;
        llama_pos positions[] = {past, past, past, 0};
        int32_t n_seq = 1;
        llama_seq_id seq = 0;
        llama_seq_id *sequences[] = {&seq};
        int8_t logits = 1;
        llama_batch batch{1, &token, nullptr, positions, &n_seq, sequences, &logits};
        if (llama_decode(context.get(), batch) != 0) throw std::runtime_error("Caption decoding stopped");
        past++;
    }
    check(request);
    return result;
}
}
