#include "qwen_core.h"
#include <fstream>
#include <iostream>
#include <vector>
int main(int argc, char **argv) {
    if (argc == 2 && std::string(argv[1]) == "--cancel-check") {
        auto id = sbs::new_request(); sbs::cancel();
        try { sbs::caption(id, "missing", "missing", nullptr, 1, 1, "test"); }
        catch (const std::exception &e) { std::cout << e.what() << '\n'; return 0; }
        return 1;
    }
    if (argc != 5) { std::cerr << "model.gguf projector.gguf image.ppm prompt.txt\n"; return 2; }
    try {
        std::ifstream image(argv[3], std::ios::binary), prompt(argv[4]);
        std::string magic; int width, height, max;
        image >> magic >> width >> height >> max; image.get();
        if (magic != "P6" || max != 255 || width < 1 || height < 1 || width > 1024 || height > 1024)
            throw std::runtime_error("Expected bounded RGB PPM fixture");
        std::vector<uint8_t> pixels(width * height * 3);
        image.read(reinterpret_cast<char *>(pixels.data()), pixels.size());
        if (!image) throw std::runtime_error("Truncated image");
        std::string text((std::istreambuf_iterator<char>(prompt)), std::istreambuf_iterator<char>());
        std::cout << sbs::caption(sbs::new_request(), argv[1], argv[2], pixels.data(), width, height, text) << '\n';
    } catch (const std::exception &e) { std::cerr << e.what() << '\n'; return 1; }
}
