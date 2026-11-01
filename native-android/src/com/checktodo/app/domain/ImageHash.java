package com.checktodo.app.domain;

/**
 * aHash 感知哈希（8x8 灰度均值哈希），纯 Java、只依赖灰度像素矩阵。
 *
 * <p>用途：AI 判定打卡的 L1 离线预检之一——当前打卡照与该任务历史打卡照的哈希
 * 汉明距离 ≤ {@link #DUPLICATE_MAX_DISTANCE} 时判为疑似重复图并拦截。</p>
 *
 * <p>算法：把灰度图重采样为 8x8（盒式平均），取 64 格的均值，
 * 每格高于均值置 1、否则置 0，得到 64 位指纹。对缩放、轻微亮度变化不敏感，
 * 对内容变化敏感——正是「同一场景的旧照片」检测需要的性质。</p>
 *
 * <p><b>纯色/近乎纯色图</b>（黑屏、白屏、遮镜头）所有格子几乎等于均值，
 * 指纹无判别力：任意两张纯色图都会「距离 0」。因此 {@link #ahashOrNull}
 * 对这类图返回 null，调用方应<b>跳过重复判定</b>而不是放行误拦。</p>
 *
 * <p>Android 侧的 Bitmap→灰度矩阵由薄壳转换（{@link #grayFromArgb} 是纯函数，可 JVM 测）。</p>
 */
public final class ImageHash {
    /** 8x8 缩略图边长。 */
    public static final int HASH_SIZE = 8;
    /** 汉明距离 ≤ 该值视为疑似重复图。 */
    public static final int DUPLICATE_MAX_DISTANCE = 5;
    /**
     * 灰度标准差低于该阈值判为「近乎纯色」，哈希不可用。
     * 正常照片（哪怕很暗/很亮）标准差远大于此值。
     */
    public static final double FLAT_STDDEV_THRESHOLD = 2.0;

    private ImageHash() {
    }

    /**
     * ARGB 像素数组 → 灰度数组（0..255）。ITU-R BT.601 亮度权重：
     * 0.299R + 0.587G + 0.114B；忽略 alpha（Bitmap.getPixels 对不透明 JPEG 恒为 255）。
     */
    public static int[] grayFromArgb(int[] argb) {
        if (argb == null) return new int[0];
        int[] gray = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int pixel = argb[i];
            int red = (pixel >> 16) & 0xFF;
            int green = (pixel >> 8) & 0xFF;
            int blue = pixel & 0xFF;
            gray[i] = (red * 77 + green * 150 + blue * 29) >> 8;
        }
        return gray;
    }

    /**
     * 计算 aHash。输入非法（null、尺寸 ≤0、像素数不足）返回 0。
     * 结果与图片分辨率无关：64x64 与 32x32 的同一图案得到同一指纹。
     */
    public static long ahash(int[] gray, int width, int height) {
        if (gray == null || width <= 0 || height <= 0 || (long) width * height > gray.length) {
            return 0L;
        }
        int[] cells = downsample8x8(gray, width, height);
        int sum = 0;
        for (int cell : cells) sum += cell;
        double mean = sum / 64.0;
        long hash = 0L;
        for (int i = 0; i < 64; i++) {
            if (cells[i] > mean) hash |= (1L << (63 - i));
        }
        return hash;
    }

    /**
     * 计算 aHash；纯色/近乎纯色或非法输入返回 null（哈希无判别力，跳过重复判定）。
     */
    public static Long ahashOrNull(int[] gray, int width, int height) {
        if (gray == null || width <= 0 || height <= 0 || (long) width * height > gray.length) {
            return null;
        }
        int[] cells = downsample8x8(gray, width, height);
        if (stddev(cells) < FLAT_STDDEV_THRESHOLD) return null;
        int sum = 0;
        for (int cell : cells) sum += cell;
        double mean = sum / 64.0;
        long hash = 0L;
        for (int i = 0; i < 64; i++) {
            if (cells[i] > mean) hash |= (1L << (63 - i));
        }
        return hash;
    }

    /** 盒式平均重采样到 8x8：每格取源图对应矩形的灰度均值。 */
    private static int[] downsample8x8(int[] gray, int width, int height) {
        int[] cells = new int[64];
        for (int cy = 0; cy < HASH_SIZE; cy++) {
            int y0 = (int) ((long) cy * height / HASH_SIZE);
            int y1 = Math.max(y0 + 1, (int) ((long) (cy + 1) * height / HASH_SIZE));
            for (int cx = 0; cx < HASH_SIZE; cx++) {
                int x0 = (int) ((long) cx * width / HASH_SIZE);
                int x1 = Math.max(x0 + 1, (int) ((long) (cx + 1) * width / HASH_SIZE));
                long sum = 0;
                int count = 0;
                for (int y = y0; y < y1 && y < height; y++) {
                    int row = y * width;
                    for (int x = x0; x < x1 && x < width; x++) {
                        sum += gray[row + x];
                        count++;
                    }
                }
                cells[cy * HASH_SIZE + cx] = count == 0 ? 0 : (int) (sum / count);
            }
        }
        return cells;
    }

    private static double stddev(int[] cells) {
        double mean = 0;
        for (int cell : cells) mean += cell;
        mean /= cells.length;
        double variance = 0;
        for (int cell : cells) variance += (cell - mean) * (cell - mean);
        return Math.sqrt(variance / cells.length);
    }

    /** 两个 64 位指纹的汉明距离（不同位的个数，0..64）。 */
    public static int hamming(long left, long right) {
        return Long.bitCount(left ^ right);
    }

    /** 汉明距离 ≤ {@link #DUPLICATE_MAX_DISTANCE} → 疑似重复图。 */
    public static boolean isDuplicate(long left, long right) {
        return hamming(left, right) <= DUPLICATE_MAX_DISTANCE;
    }

    /** 指纹 → 16 位小写 hex（持久化格式）。 */
    public static String toHex(long hash) {
        StringBuilder builder = new StringBuilder(16);
        for (int i = 60; i >= 0; i -= 4) {
            builder.append(Character.forDigit((int) ((hash >>> i) & 0xF), 16));
        }
        return builder.toString();
    }

    /** 16 位 hex → 指纹；非法输入返回 null（历史脏数据要能容忍）。 */
    public static Long fromHex(String hex) {
        if (hex == null || hex.length() != 16) return null;
        long value = 0L;
        for (int i = 0; i < 16; i++) {
            int digit = Character.digit(hex.charAt(i), 16);
            if (digit < 0) return null;
            value = (value << 4) | digit;
        }
        return value;
    }

    /**
     * 两个 hex 指纹的汉明距离；任一不可解析返回 -1（调用方按「无法比较」处理）。
     */
    public static int hamming(String leftHex, String rightHex) {
        Long left = fromHex(leftHex);
        Long right = fromHex(rightHex);
        if (left == null || right == null) return -1;
        return hamming(left, right);
    }
}
