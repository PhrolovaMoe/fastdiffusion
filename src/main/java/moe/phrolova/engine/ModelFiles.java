package moe.phrolova.engine;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import moe.phrolova.Config;
import org.slf4j.Logger;

/**
 * Obtains the model: downloads the original MIT-licensed PyTorch checkpoints of Terrain Diffusion
 * (xandergos) at a pinned revision, verifies them and converts them natively into GGUF.
 */
final class ModelFiles {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String WEIGHTS = "https://huggingface.co/xandergos/terrain-diffusion-30m/resolve/9ef8030cb805b433b98ec25c5dddefbac07a9e26/";
    private static final String PIPELINE = "https://huggingface.co/xandergos/terrain-diffusion-30m-onnx/resolve/ad2df557eca5645f588766101cf3bc3682455c3e/";

    private record Asset(String url, String path, long size, String sha256) {}

    private static final List<Asset> ASSETS = List.of(
            new Asset(WEIGHTS + "config.json", "config.json", 774, "c60f0b74d89317e64cfc623fbfdd828f1b5b2e50aa75020ac4001103381853bd"),
            new Asset(WEIGHTS + "coarse_model/config.json", "coarse_model/config.json", 828,
                    "3256a71d181dc3b10f7e44320920694b6aba1572ee5200872a503f89c8cbb3b4"),
            new Asset(WEIGHTS + "coarse_model/diffusion_pytorch_model.safetensors", "coarse_model/diffusion_pytorch_model.safetensors", 11_200_936,
                    "f88cdc26c70a2c73ee4c91088b8cec3f45202fc452682ac759554b099c5c2f33"),
            new Asset(WEIGHTS + "base_model/config.json", "base_model/config.json", 682,
                    "2400da3c7bed5dbc331a0cfabd3f4a5a3c5c977d4c4b3fb1037f4e285d320598"),
            new Asset(WEIGHTS + "base_model/diffusion_pytorch_model.safetensors", "base_model/diffusion_pytorch_model.safetensors", 1_014_772_076,
                    "351d1b1a77cf32e15adc4f72ed5fd26d317340598329d35f5dce2ff6dbcce735"),
            new Asset(WEIGHTS + "decoder_model/config.json", "decoder_model/config.json", 609,
                    "fc69e7c75bcc8b92d687306cb38cca2868a6034cdf73ddf7cfbf7078af6ac55e"),
            new Asset(WEIGHTS + "decoder_model/diffusion_pytorch_model.safetensors", "decoder_model/diffusion_pytorch_model.safetensors", 111_709_108,
                    "b6c7fa99f836ad75c514236c9529e18a68ea207ed59dd39fd1341fc9a8a03bcc"),
            new Asset(PIPELINE + "pipeline_data.json", "pipeline_data.json", 12_226,
                    "e3132c3ef0c65d8613615f9278ffe23bbd9363ddcd87f1cc6f18456bcc9efe5c"));

    private static final long TOTAL_BYTES = ASSETS.stream().mapToLong(Asset::size).sum();

    private ModelFiles() {}

    static Path ensureModel(Path dataDir, Path model) {
        if (Files.isRegularFile(model)) return model;
        if (!Config.autoDownload()) {
            throw new IllegalStateException("FastDiffusion model not found at " + model
                    + ". Enable autoDownload in the config or point modelFile at a converted GGUF.");
        }
        Path source = dataDir.resolve("models").resolve("download");
        try {
            Files.createDirectories(source);
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(20)).build();
            // Files verified by an earlier, interrupted attempt count as downloaded from the start.
            List<Asset> missing = new ArrayList<>();
            long present = 0;
            for (Asset a : ASSETS) {
                Path target = source.resolve(a.path);
                if (Files.isRegularFile(target) && sha256(target).equals(a.sha256)) present += a.size;
                else missing.add(a);
            }
            ModelProgress.download(present, TOTAL_BYTES);
            for (Asset a : missing) fetch(http, a, source.resolve(a.path));
            Files.createDirectories(model.toAbsolutePath().getParent());
            LOGGER.info("Converting model to {}", model);
            ModelProgress.set(ModelProgress.Stage.CONVERTING);
            NativeBridge.convertModel(source.toString(), source.resolve("pipeline_data.json").toString(), model.toString());
            deleteTree(source);
            return model;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FastDiffusion model download interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("could not download the FastDiffusion model; check your connection or place "
                    + model.getFileName() + " manually", e);
        }
    }

    private static void fetch(HttpClient http, Asset a, Path target) throws IOException, InterruptedException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        LOGGER.info("Downloading {}", a.url);
        HttpResponse<InputStream> resp = http.send(HttpRequest.newBuilder(URI.create(a.url))
                .timeout(Duration.ofMinutes(1)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        MessageDigest md = digest();
        long before = ModelProgress.done();
        try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(tmp); StallGuard guard = new StallGuard(in)) {
            if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + " for " + a.url);
            byte[] buf = new byte[1 << 16];
            int logged = (int) (before * 10 / TOTAL_BYTES);
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                md.update(buf, 0, n);
                ModelProgress.advance(n);
                int pct = (int) (ModelProgress.done() * 100 / TOTAL_BYTES);
                if (a.size > (64 << 20) && pct / 10 > logged) {
                    logged = pct / 10;
                    LOGGER.info("Model download {}%", pct);
                }
            }
            if (guard.stalled) throw new IOException("download of " + a.path + " stalled");
        }
        String got = HexFormat.of().formatHex(md.digest());
        if (!got.equals(a.sha256)) {
            Files.deleteIfExists(tmp);
            throw new IOException("checksum mismatch for " + a.path + " (got " + got + ")");
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        ModelProgress.download(before + a.size, TOTAL_BYTES);
        LOGGER.info("Verified {} ({} MiB)", a.path, a.size >> 20);
    }

    /** Closes a response body that delivers nothing for a minute; the request timeout only covers the headers. */
    private static final class StallGuard implements AutoCloseable {
        private final Thread thread;
        volatile boolean stalled;

        StallGuard(InputStream in) {
            thread = Thread.ofPlatform().daemon().name("FastDiffusion download watchdog").start(() -> {
                long last = -1;
                try {
                    while (true) {
                        Thread.sleep(60_000);
                        long now = ModelProgress.done();
                        if (now == last) {
                            stalled = true;
                            in.close();
                            return;
                        }
                        last = now;
                    }
                } catch (InterruptedException | IOException ignored) {
                }
            });
        }

        @Override
        public void close() {
            thread.interrupt();
        }
    }

    private static String sha256(Path p) throws IOException {
        MessageDigest md = digest();
        try (InputStream in = new DigestInputStream(Files.newInputStream(p), md)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
