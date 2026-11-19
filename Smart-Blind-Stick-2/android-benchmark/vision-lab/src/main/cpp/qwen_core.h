#pragma once
#include <cstdint>
#include <string>

namespace sbs {
int64_t new_request();
void cancel();
std::string caption(int64_t request, const std::string &model, const std::string &projector,
                    const uint8_t *rgb, int width, int height, const std::string &prompt,
                    int max_tokens = 128);
}
