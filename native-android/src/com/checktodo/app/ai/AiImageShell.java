package com.checktodo.app.ai;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.util.Base64;

import com.checktodo.app.domain.ImageHash;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 图片处理薄壳：把 Android 的 Bitmap / ExifInterface / Base64 转成域层纯函数
 * （{@link ImageHash}、{@link com.checktodo.app.domain.GlmRequest}）能吃的输入。
 *
 * <p><b>职责边界</b>：本类不含任何判定逻辑——只做「像素矩阵提取、缩放、压缩、
 * base64、EXIF 时刻读取」，判定全部在域层（可 JVM 测）。</p>
 */
public final class AiImageShell {

    /** 上传前长边缩放上限：控制 base64 体积与模型输入尺寸。 */
    public static final int MAX_LONG_EDGE = 512;
    /** JPEG 压缩质量（0..100）。 */
    public static final int JPEG_QUALITY = 82;
    /** 解码采样上限：先按 inSampleSize 粗降到长边 ≤2048，再精确缩放，避免大图 OOM。 */
    private static final int DECODE_LIMIT = 2048;

    private AiImageShell() {
    }

    /**
     * 文件 → ARGB 像素矩阵 + 尺寸（用于域层算 aHash）。
     *
     * <p>返回 {@code Sampled}（像素、宽、高）；文件不存在或解码失败返回 null
     * ——调用方据此走「哈希不可用 → 跳过去重」的放行路径，绝不因解码失败误拦。</p>
     */
    public static Sampled samplePixels(File file) {
        if (file == null || !file.exists()) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= DECODE_LIMIT) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = null;
        try {
            bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bitmap == null) return null;
            int[] argb = new int[bitmap.getWidth() * bitmap.getHeight()];
            bitmap.getPixels(argb, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
            return new Sampled(argb, bitmap.getWidth(), bitmap.getHeight());
        } catch (OutOfMemoryError error) {
            return null;
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    /** 像素矩阵与尺寸（域层 grayFromArgb / ahashOrNull 的直接输入）。 */
    public static final class Sampled {
        public final int[] argb;
        public final int width;
        public final int height;

        Sampled(int[] argb, int width, int height) {
            this.argb = argb;
            this.width = width;
            this.height = height;
        }
    }

    /** 文件 → aHash hex；解码失败或纯色图（哈希不可用）返回 null。 */
    public static String hashHexOrNull(File file) {
        Sampled sampled = samplePixels(file);
        if (sampled == null) return null;
        int[] gray = ImageHash.grayFromArgb(sampled.argb);
        Long hash = ImageHash.ahashOrNull(gray, sampled.width, sampled.height);
        return hash == null ? null : ImageHash.toHex(hash);
    }

    /**
     * 文件 → base64 data URI（长边缩放到 {@link #MAX_LONG_EDGE}、JPEG 压缩后编码）。
     *
     * @return data URI；文件缺失或解码失败返回 null（调用方走降级）
     */
    public static String dataUriOrNull(File file) {
        if (file == null || !file.exists()) return null;
        Bitmap source = null;
        Bitmap scaled = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= DECODE_LIMIT) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            source = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (source == null) return null;
            scaled = scaleLongEdge(source, MAX_LONG_EDGE);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, buffer);
            String base64 = Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP);
            return com.checktodo.app.domain.GlmRequest.dataUri(base64, "image/jpeg");
        } catch (OutOfMemoryError error) {
            return null;
        } finally {
            if (scaled != null && scaled != source) scaled.recycle();
            if (source != null) source.recycle();
        }
    }

    /**
     * 长边缩放到不超过 maxEdge（小图不放大）。
     * 缩放算法用最朴素的 createScaledBitmap（双线性），无第三方依赖。
     */
    public static Bitmap scaleLongEdge(Bitmap source, int maxEdge) {
        int longEdge = Math.max(source.getWidth(), source.getHeight());
        if (longEdge <= maxEdge || longEdge <= 0) return source;
        float ratio = (float) maxEdge / (float) longEdge;
        int width = Math.max(1, Math.round(source.getWidth() * ratio));
        int height = Math.max(1, Math.round(source.getHeight() * ratio));
        return Bitmap.createScaledBitmap(source, width, height, true);
    }

    /**
     * 读取 EXIF 拍摄时刻（{@code DateTimeOriginal}）。
     *
     * <p>无 EXIF、字段缺失或时间不可解析一律返回 null——域层
     * {@link com.checktodo.app.domain.ExifFreshness} 把 null 判为 UNKNOWN 并放行，
     * 因此这里任何失败都不会误拦正常打卡。</p>
     */
    public static Long shotAtMillisOrNull(File file) {
        if (file == null || !file.exists()) return null;
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            String raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
            if (raw == null || raw.trim().isEmpty()) {
                raw = exif.getAttribute(ExifInterface.TAG_DATETIME);
            }
            if (raw == null || raw.trim().isEmpty()) return null;
            return parseExifTime(raw.trim());
        } catch (IOException exception) {
            return null;
        }
    }

    /** EXIF 时间格式 {@code yyyy:MM:dd HH:mm:ss}；不可解析返回 null。 */
    public static Long parseExifTime(String raw) {
        String[] patterns = {"yyyy:MM:dd HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy:MM:dd HH:mm"};
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                format.setLenient(false);
                Date parsed = format.parse(raw);
                if (parsed != null) return parsed.getTime();
            } catch (ParseException ignored) {
                // 试下一个格式
            } catch (IllegalArgumentException ignored) {
                // 非法 pattern（不会发生）
            }
        }
        return null;
    }
}
