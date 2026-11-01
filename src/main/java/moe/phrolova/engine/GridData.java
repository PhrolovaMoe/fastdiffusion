package moe.phrolova.engine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A map grid from the native sampler (layout fixed by native/src/pipeline.h): planar int16 top block Y,
 * then uint8 biome id, encoded temperature and encoded precipitation per pixel.
 */
public final class GridData {
    public final int width, height;
    private final ByteBuffer buf;

    GridData(int width, int height, ByteBuffer buf) {
        this.width = width;
        this.height = height;
        this.buf = buf.order(ByteOrder.LITTLE_ENDIAN);
    }

    static int bytes(int width, int height) {
        return width * height * 5;
    }

    public int y(int x, int z) {
        return buf.getShort((z * width + x) << 1);
    }

    public int biome(int x, int z) {
        return buf.get(width * height * 2 + z * width + x) & 0xff;
    }

    public int tempCode(int x, int z) {
        return buf.get(width * height * 3 + z * width + x) & 0xff;
    }

    public int precipCode(int x, int z) {
        return buf.get(width * height * 4 + z * width + x) & 0xff;
    }

    /** Degrees Celsius of an encoded temperature: 0.5 degC steps from -50 degC. */
    public static float temperature(int code) {
        return code * 0.5f - 50.0f;
    }

    /** Millimeters per year of an encoded precipitation: 20 mm steps. */
    public static float precipitation(int code) {
        return code * 20.0f;
    }
}
