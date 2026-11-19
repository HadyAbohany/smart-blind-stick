package eg.sbs.benchmark;

import android.content.Context;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** Models stay app-scoped and offline. Every file is verified before native/ORT loading. */
final class ModelStore {
    record Entry(String role, String file, long bytes, String sha256) {}
    private final Context context;
    private final File directory;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Long> verified = new LinkedHashMap<>();

    ModelStore(Context context) throws IOException {
        this.context = context;
        directory = new File(context.getExternalFilesDir(null), "models");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Model folder unavailable");
        try (var stream = context.getAssets().open("vision-models.json")) {
            var data = new JSONObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            var models = data.getJSONArray("models");
            for (int i = 0; i < models.length(); i++) {
                var m = models.getJSONObject(i);
                var e = new Entry(m.getString("role"), m.getString("file"), m.getLong("bytes"), m.getString("sha256"));
                if (!e.file().matches("[A-Za-z0-9_.-]+") || !e.sha256().matches("[a-f0-9]{64}") || e.bytes() < 1)
                    throw new IOException("Invalid model manifest");
                entries.put(e.role(), e);
            }
        } catch (org.json.JSONException error) { throw new IOException("Invalid model manifest", error); }
    }

    File require(String role) throws Exception {
        var e = entries.get(role);
        if (e == null) throw new IOException("Unknown model role");
        var file = new File(directory, e.file());
        if (!file.isFile() || file.length() != e.bytes()) throw new IOException("Missing model: " + e.file());
        long modified = file.lastModified();
        if (verified.getOrDefault(role, -1L) != modified) {
            try (var input = new FileInputStream(file)) {
                if (!digest(input, null, e.bytes()).equals(e.sha256())) throw new IOException("Model checksum mismatch: " + e.file());
            }
            if (modified != file.lastModified() || file.length() != e.bytes()) throw new IOException("Model changed during verification");
            verified.put(role, modified);
        }
        return file;
    }

    String status() {
        var text = new StringBuilder();
        for (var e : entries.values()) {
            var file = new File(directory, e.file());
            boolean present = file.isFile() && file.length() == e.bytes();
            text.append(present ? "✓ " : "○ ").append(e.role()).append(" · ")
                    .append(Math.round(e.bytes() / 1e6)).append(" MB\n");
        }
        return text.toString();
    }

    void importFile(Uri uri) throws Exception {
        String name = null;
        try (var cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        }
        final String requested = name;
        Entry entry = entries.values().stream().filter(e -> e.file().equals(requested)).findFirst()
                .orElseThrow(() -> new IOException("Choose a file from the prepared model pack"));
        File candidate = new File(directory, entry.file() + ".partial");
        try {
            String hash;
            try (var input = context.getContentResolver().openInputStream(uri); var output = new FileOutputStream(candidate)) {
                if (input == null) throw new IOException("Cannot open model");
                hash = digest(input, output, entry.bytes());
                output.getFD().sync();
            }
            if (candidate.length() != entry.bytes() || !hash.equals(entry.sha256())) throw new IOException("Model file failed integrity check");
            java.nio.file.Files.move(candidate.toPath(), new File(directory, entry.file()).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            verified.remove(entry.role());
        } finally { if (candidate.exists()) candidate.delete(); }
    }

    private static String digest(java.io.InputStream input, java.io.OutputStream output, long maximum) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1024 * 1024];
        long total = 0;
        for (int count; (count = input.read(buffer)) != -1;) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Model operation canceled");
            total += count;
            if (total > maximum) throw new IOException("Model is larger than expected");
            digest.update(buffer, 0, count);
            if (output != null) output.write(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }
}
