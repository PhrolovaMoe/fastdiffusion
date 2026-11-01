package moe.phrolova.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;

/**
 * Which chunks exist on disk (read from region file headers) plus chunks generated since, streamed live.
 * Region headers list every saved chunk, including partly generated ones at the edge of explored areas.
 */
final class ChunkLayer {
    private static final int MAX_REGIONS = 64 * 64;

    private record Generated(String dim, int cx, int cz) {}

    private final Events events;
    private final ConcurrentLinkedQueue<Generated> pending = new ConcurrentLinkedQueue<>();

    ChunkLayer(Events events) {
        this.events = events;
    }

    /** Any thread: a chunk was generated and loaded for the first time. */
    void generated(String dim, int cx, int cz) {
        if (events.hasClients()) pending.add(new Generated(dim, cx, cz));
    }

    /** Server thread, every few ticks: one event per dimension with flat [cx, cz, ...] pairs. */
    void flush() {
        if (pending.isEmpty()) return;
        Map<String, JsonArray> byDim = new HashMap<>();
        Generated g;
        while ((g = pending.poll()) != null) {
            JsonArray a = byDim.computeIfAbsent(g.dim, d -> new JsonArray());
            a.add(g.cx);
            a.add(g.cz);
        }
        byDim.forEach((dim, chunks) -> {
            JsonObject o = new JsonObject();
            o.addProperty("dim", dim);
            o.add("chunks", chunks);
            events.broadcast("chunks", o);
        });
    }

    /** Saved chunks of the region files overlapping a block rectangle, as 1024-bit masks (bit = z * 32 + x). */
    JsonObject saved(MapWorld w, double x0, double z0, double x1, double z1) throws IOException {
        int rx0 = (int) Math.floor(Math.min(x0, x1) / 512), rx1 = (int) Math.floor(Math.max(x0, x1) / 512);
        int rz0 = (int) Math.floor(Math.min(z0, z1) / 512), rz1 = (int) Math.floor(Math.max(z0, z1) / 512);
        if ((long) (rx1 - rx0 + 1) * (rz1 - rz0 + 1) > MAX_REGIONS) throw Http.badRequest("area too large; zoom in");
        Path dir = w.saveDir().resolve("region");
        JsonArray regions = new JsonArray();
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                s.forEach(files::add);
            }
        }
        long bytes = 0, chunks = 0;
        for (Path f : files) {
            String[] parts = f.getFileName().toString().split("\\.");
            if (parts.length != 4 || !parts[0].equals("r") || !parts[3].equals("mca")) continue;
            int rx, rz;
            try {
                rx = Integer.parseInt(parts[1]);
                rz = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                continue;
            }
            boolean inside = rx >= rx0 && rx <= rx1 && rz >= rz0 && rz <= rz1;
            // Files outside the window are only sampled, for the bytes-per-chunk estimate.
            long size = Files.size(f);
            if (!inside && chunks > 20_000) continue;
            byte[] header;
            try (InputStream in = Files.newInputStream(f)) {
                header = in.readNBytes(4096);
            }
            if (header.length < 4096) continue;
            byte[] mask = new byte[128];
            int count = 0;
            for (int i = 0; i < 1024; i++) {
                int off = i * 4;
                if ((header[off] | header[off + 1] | header[off + 2] | header[off + 3]) != 0) {
                    mask[i >> 3] |= (byte) (1 << (i & 7));
                    count++;
                }
            }
            bytes += size;
            chunks += count;
            if (!inside || count == 0) continue;
            JsonObject o = new JsonObject();
            o.addProperty("rx", rx);
            o.addProperty("rz", rz);
            o.addProperty("count", count);
            o.addProperty("mask", Base64.getEncoder().encodeToString(mask));
            regions.add(o);
        }
        JsonObject out = new JsonObject();
        out.add("regions", regions);
        out.addProperty("bytesPerChunk", chunks > 0 ? bytes / chunks : 0);
        return out;
    }
}
