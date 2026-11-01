package com.checktodo.app.ai;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * AI 判定照片的私有目录管理（薄壳）。
 *
 * <p>照片一律存 {@code getFilesDir()/ai_photos/}——app 私有目录，无需任何存储权限，
 * 卸载即清除。文件名带毫秒时间戳，避免同日多张互相覆盖。</p>
 *
 * <p><b>相册渠道（N+12）</b>：{@code ACTION_GET_CONTENT} 返回的 Uri 只是<b>临时授权</b>
 * （activity 结束即失效），而 GLM 请求需要把图片读成 base64——因此相册图必须先用
 * {@link #copyFromUri(Context, Uri, String)} <b>流式拷进私有目录</b>，之后与拍照图
 * 走完全相同的链路（采样 → aHash → EXIF → data URI）。拷贝失败返回 null，
 * 调用方按「没有图」处理并提示，绝不把失败当成有图。</p>
 */
public final class AiPhotoStore {

    /** 私有照片子目录名（Provider 的路径校验也认这个名字）。 */
    public static final String DIR_NAME = "ai_photos";
    /** 拷贝缓冲：32KB，兼顾大图吞吐与内存占用。 */
    private static final int COPY_BUFFER = 32 * 1024;
    private static final String TAG = "CheckTodoAi";

    private AiPhotoStore() {
    }

    /** 照片目录（不存在则创建）。 */
    public static File dir(Context context) {
        File dir = new File(context.getFilesDir(), DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            // 创建失败时退回 filesDir 本身，宁可路径不优雅也不让打卡流程崩。
            return context.getFilesDir();
        }
        return dir;
    }

    /** 新建一张照片的目标文件：ref-*.jpg（标准照）/ shot-*.jpg（打卡照）。 */
    public static File newFile(Context context, String prefix) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
        return new File(dir(context), prefix + "-" + stamp + ".jpg");
    }

    /** 标准照文件。 */
    public static File newReferenceFile(Context context) {
        return newFile(context, "ref");
    }

    /** 打卡当前照文件。 */
    public static File newShotFile(Context context) {
        return newFile(context, "shot");
    }

    /** 按渠道前缀建目标文件（相册渠道得到 gal-shot-* / gal-ref-*，便于审计区分来源）。 */
    public static File newFile(Context context, String prefix,
                               com.checktodo.app.domain.PhotoChannel channel) {
        return newFile(context, com.checktodo.app.domain.PhotoChannel.orCamera(channel)
                .filePrefix(prefix));
    }

    /**
     * 把相册 Uri 的图片<b>流式拷进私有目录</b>。
     *
     * <p>不做解码、不做缩放——原样落盘，后续 {@link AiImageShell} 会按既有口径
     * 采样解码（DECODE_LIMIT 2048 + 长边 512 + JPEG 82），与拍照图一致。</p>
     *
     * @param context 上下文
     * @param uri     相册返回的内容 Uri（{@code ACTION_GET_CONTENT} / {@code ACTION_PICK}）
     * @param prefix  目标文件名前缀（如 {@code gal-shot}）；空白回落 {@code img}
     * @return 拷贝完成的私有文件；uri 为 null、读取失败、写出 0 字节时返回 null（已清理残留）
     */
    public static File copyFromUri(Context context, Uri uri, String prefix) {
        if (context == null || uri == null) return null;
        String safePrefix = prefix == null || prefix.trim().isEmpty() ? "img" : prefix.trim();
        File target = newFile(context, safePrefix);
        InputStream input = null;
        OutputStream output = null;
        try {
            input = context.getContentResolver().openInputStream(uri);
            if (input == null) {
                Log.w(TAG, "album copy skipped: content resolver returned null stream");
                return null;
            }
            output = new FileOutputStream(target);
            byte[] buffer = new byte[COPY_BUFFER];
            long total = 0L;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                total += read;
            }
            output.flush();
            if (total <= 0L) {
                Log.w(TAG, "album copy rejected: empty content");
                closeQuietly(output);
                output = null;
                deleteQuietly(target);
                return null;
            }
            Log.i(TAG, "album photo copied: bytes=" + total + ", name=" + target.getName());
            return target;
        } catch (Exception exception) {
            // 安全网：相册 Uri 可能因权限过期/媒体库变动读取失败，一律按「没有图」处理。
            Log.w(TAG, "album copy failed: " + exception.getClass().getSimpleName());
            closeQuietly(output);
            output = null;
            deleteQuietly(target);
            return null;
        } finally {
            closeQuietly(input);
            closeQuietly(output);
        }
    }

    /** 相册渠道便捷入口：文件名前缀由渠道决定（{@code gal-shot} / {@code gal-ref}）。 */
    public static File copyFromUri(Context context, Uri uri) {
        return copyFromUri(context, uri,
                com.checktodo.app.domain.PhotoChannel.GALLERY.filePrefix("img"));
    }

    private static void closeQuietly(java.io.Closeable stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (Exception ignored) {
            // 关流失败无需上报：拷贝结果已由返回值体现。
        }
    }

    private static void deleteQuietly(File file) {
        if (file == null || !file.exists()) return;
        try {
            if (!file.delete()) {
                Log.w(TAG, "leftover album file not deleted: " + file.getName());
            }
        } catch (Exception ignored) {
            // 残留文件不影响判定（无引用即不可读），不阻塞打卡。
        }
    }
}
