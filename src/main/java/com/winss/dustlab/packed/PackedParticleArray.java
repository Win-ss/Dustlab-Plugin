package com.winss.dustlab.packed;

import com.winss.dustlab.models.ParticleData;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.RandomAccess;

/**
 * Memory-compact representation of particle data backed by primitive arrays.
 * Retains compatibility with the legacy {@link ParticleData} based pipeline via adapter views.
 */
public final class PackedParticleArray {

    private final float[] x;
    private final float[] y;
    private final float[] z;
    private final float[] r;
    private final float[] g;
    private final float[] b;
    private final float[] scale;
    private final int[] delay;
    private final int size;

    private PackedParticleArray(float[] x, float[] y, float[] z,
                                float[] r, float[] g, float[] b,
                                float[] scale, int[] delay,
                                int size) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.r = r;
        this.g = g;
        this.b = b;
        this.scale = scale;
        this.delay = delay;
        this.size = size;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public double getX(int index) {
        return x[index];
    }

    public double getY(int index) {
        return y[index];
    }

    public double getZ(int index) {
        return z[index];
    }

    public double getR(int index) {
        return r[index];
    }

    public double getG(int index) {
        return g[index];
    }

    public double getB(int index) {
        return b[index];
    }

    public float getScale(int index) {
        return scale[index];
    }

    public int getDelay(int index) {
        return delay[index];
    }

    /**
     * Provides a lightweight {@link List} view that materialises {@link ParticleData} on demand.
     * Useful for sites that still expect the legacy representation while avoiding full object retention.
     */
    public List<ParticleData> toParticleDataList() {
        if (isEmpty()) {
            return Collections.emptyList();
        }
        return new PackedParticleListView(this);
    }

    /**
     * Populates the supplied {@link ParticleData} instance with the packed values at {@code index}.
     * This allows re-using a single {@link ParticleData} object across iterations to avoid garbage.
     */
    public void copyInto(int index, ParticleData reusable) {
        reusable.setX(getX(index));
        reusable.setY(getY(index));
        reusable.setZ(getZ(index));
        reusable.setR(getR(index));
        reusable.setG(getG(index));
        reusable.setB(getB(index));
        reusable.setDelay(getDelay(index));
        reusable.setScale(getScale(index));
    }

    /**
     * Provides a lightweight approximation of the heap footprint for this packed array.
     * The calculation focuses on the primitive backing arrays which dominate usage.
     */
    public long approximateSizeBytes() {
        long componentBytes = (long) size * (Float.BYTES * 7L + Integer.BYTES);
        long arrayHeaders = 16L * 8L; // rough JVM header per primitive array
        return componentBytes + arrayHeaders;
    }

    public static Builder builder() {
        return new Builder(1024);
    }

    public static Builder builder(int expectedSize) {
        return new Builder(expectedSize);
    }

    public static final class Builder {
        private float[] x;
        private float[] y;
        private float[] z;
        private float[] r;
        private float[] g;
        private float[] b;
        private float[] scale;
        private int[] delay;
        private int size;

        private Builder(int expectedSize) {
            int initial = Math.max(0, expectedSize);
            this.x = new float[initial];
            this.y = new float[initial];
            this.z = new float[initial];
            this.r = new float[initial];
            this.g = new float[initial];
            this.b = new float[initial];
            this.scale = new float[initial];
            this.delay = new int[initial];
            this.size = 0;
        }

        private void ensureCapacity(int minCapacity) {
            if (minCapacity <= x.length) {
                return;
            }
            int newCapacity = Math.max(minCapacity, x.length == 0 ? 1024 : x.length * 2);
            x = Arrays.copyOf(x, newCapacity);
            y = Arrays.copyOf(y, newCapacity);
            z = Arrays.copyOf(z, newCapacity);
            r = Arrays.copyOf(r, newCapacity);
            g = Arrays.copyOf(g, newCapacity);
            b = Arrays.copyOf(b, newCapacity);
            scale = Arrays.copyOf(scale, newCapacity);
            delay = Arrays.copyOf(delay, newCapacity);
        }

        public Builder add(ParticleData data) {
            if (data == null) {
                return this;
            }
            return add(data.getX(), data.getY(), data.getZ(), data.getR(), data.getG(), data.getB(), data.getDelay(), data.getScale());
        }

        public Builder add(double x, double y, double z,
                           double r, double g, double b,
                           int delay, float scale) {
            ensureCapacity(size + 1);
            int idx = size++;
            this.x[idx] = (float) x;
            this.y[idx] = (float) y;
            this.z[idx] = (float) z;
            this.r[idx] = (float) r;
            this.g[idx] = (float) g;
            this.b[idx] = (float) b;
            this.scale[idx] = scale;
            this.delay[idx] = delay;
            return this;
        }

        public Builder add(double x, double y, double z,
                           double r, double g, double b,
                           int delay, double scale) {
            return add(x, y, z, r, g, b, delay, (float) scale);
        }

        public int size() {
            return size;
        }

        public PackedParticleArray build() {
            float[] fx = Arrays.copyOf(x, size);
            float[] fy = Arrays.copyOf(y, size);
            float[] fz = Arrays.copyOf(z, size);
            float[] fr = Arrays.copyOf(r, size);
            float[] fg = Arrays.copyOf(g, size);
            float[] fb = Arrays.copyOf(b, size);
            float[] fs = Arrays.copyOf(scale, size);
            int[] fd = Arrays.copyOf(delay, size);
            return new PackedParticleArray(fx, fy, fz, fr, fg, fb, fs, fd, size);
        }
    }

    private static final class PackedParticleListView extends AbstractList<ParticleData> implements RandomAccess {
        private final PackedParticleArray array;

        private PackedParticleListView(PackedParticleArray array) {
            this.array = array;
        }

        @Override
        public ParticleData get(int index) {
            if (index < 0 || index >= array.size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + array.size);
            }
            ParticleData data = new ParticleData(array.getX(index), array.getY(index), array.getZ(index),
                    array.getR(index), array.getG(index), array.getB(index));
            data.setDelay(array.getDelay(index));
            data.setScale(array.getScale(index));
            return data;
        }

        @Override
        public int size() {
            return array.size;
        }

        @Override
        public boolean isEmpty() {
            return array.isEmpty();
        }
    }
}
