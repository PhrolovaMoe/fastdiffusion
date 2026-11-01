package moe.phrolova.engine;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.Logger;

/** Extracts the bundled native library into the data directory and loads it. */
final class NativeLibrary {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String RESOURCE = "/natives/linux-x86_64/libfastdiffusion.so";
    private static boolean loaded;

    private NativeLibrary() {}

    static synchronized void load(Path dataDir) {
        if (loaded) return;
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch");
        if (!os.contains("linux") || !(arch.equals("amd64") || arch.equals("x86_64"))) {
            throw new IllegalStateException("FastDiffusion only supports Linux x86_64 (found " + os + "/" + arch + ")");
        }
        try (InputStream in = NativeLibrary.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("native library missing from the mod jar: " + RESOURCE);
            byte[] bytes = in.readAllBytes();
            // Content-addressed so a mod update never loads a stale library.
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
            Path target = dataDir.resolve("natives").resolve(hash).resolve("libfastdiffusion.so");
            if (!Files.isRegularFile(target) || Files.size(target) != bytes.length) {
                Files.createDirectories(target.getParent());
                Path tmp = Files.createTempFile(target.getParent(), "lib", ".tmp");
                Files.write(tmp, bytes);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            System.load(target.toAbsolutePath().toString());
            loaded = true;
            LOGGER.info("Loaded native library {}", target);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("failed to extract the FastDiffusion native library", e);
        }
    }
}
