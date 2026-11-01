package moe.phrolova.engine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * One generated 256x256-block region (layout fixed by native/src/pipeline.h):
 * int16 top-solid-block Y per column, uint8 slope*32 per column, then per 4x4 cell uint8 biome id,
 * temperature and precipitation (encoded as in {@link GridData}).
 */
public final class RegionData {
    public static final int SIZE = 256;
    public static final int SHIFT = 8;
    private static final int SLOPE_OFFSET = SIZE * SIZE * 2;
    private static final int BIOME_OFFSET = SLOPE_OFFSET + SIZE * SIZE;
    private static final int CELLS = (SIZE / 4) * (SIZE / 4);
    private static final int TEMP_OFFSET = BIOME_OFFSET + CELLS;
    private static final int PRECIP_OFFSET = TEMP_OFFSET + CELLS;
    static final int BYTES = PRECIP_OFFSET + CELLS;

    public final int rx, rz;
    private final ByteBuffer buf;

    RegionData(int rx, int rz, ByteBuffer buf) {
        this.rx = rx;
        this.rz = rz;
        this.buf = buf.order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Y of the highest solid block; local coordinates in [0, 256). */
    public int height(int lx, int lz) {
        return buf.getShort(((lz << SHIFT) | lx) << 1);
    }

    /** Terrain slope in blocks per block. */
    public float slope(int lx, int lz) {
        return (buf.get(SLOPE_OFFSET + ((lz << SHIFT) | lx)) & 0xff) / 32.0f;
    }

    /** Biome id of the 4x4 cell containing the local column. */
    public int biome(int lx, int lz) {
        return buf.get(BIOME_OFFSET + (((lz >> 2) << 6) | (lx >> 2))) & 0xff;
    }

    /** Encoded temperature of the 4x4 cell containing the local column, see {@link GridData#temperature}. */
    public int tempCode(int lx, int lz) {
        return buf.get(TEMP_OFFSET + (((lz >> 2) << 6) | (lx >> 2))) & 0xff;
    }

    /** Encoded precipitation of the 4x4 cell containing the local column, see {@link GridData#precipitation}. */
    public int precipCode(int lx, int lz) {
        return buf.get(PRECIP_OFFSET + (((lz >> 2) << 6) | (lx >> 2))) & 0xff;
    }
}
