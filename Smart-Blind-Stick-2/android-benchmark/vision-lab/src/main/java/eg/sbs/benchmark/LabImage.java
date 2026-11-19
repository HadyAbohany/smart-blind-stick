package eg.sbs.benchmark;

import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

final class LabImage {
    static Bitmap decode(byte[] jpeg) throws IOException {
        if (jpeg == null || jpeg.length < 3 || jpeg.length > 32 * 1024 * 1024) throw new IOException("Invalid JPEG");
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(jpeg)), (decoder, info, source) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
            var size = info.getSize();
            float scale = Math.min(1, 1024f / Math.max(size.getWidth(), size.getHeight()));
            decoder.setTargetSize(Math.max(1, Math.round(size.getWidth() * scale)), Math.max(1, Math.round(size.getHeight() * scale)));
        });
    }
    static Bitmap rotate(Bitmap source, int degrees) {
        if (degrees == 0) return source;
        var matrix = new Matrix(); matrix.postRotate(degrees);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }
    static byte[] jpeg(Bitmap bitmap) {
        var output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output);
        return output.toByteArray();
    }
    static byte[] rgb(Bitmap image) {
        int[] pixels = new int[image.getWidth() * image.getHeight()];
        image.getPixels(pixels, 0, image.getWidth(), 0, 0, image.getWidth(), image.getHeight());
        byte[] bytes = new byte[pixels.length * 3];
        for (int i = 0; i < pixels.length; i++) {
            bytes[3*i] = (byte) (pixels[i] >> 16); bytes[3*i+1] = (byte) (pixels[i] >> 8); bytes[3*i+2] = (byte) pixels[i];
        }
        return bytes;
    }
    static int depthDimension(int original, int longest) {
        return Math.max(14, Math.round(original * 518f / longest / 14) * 14);
    }
}
