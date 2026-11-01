package com.checktodo.app.ai;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * 私有照片目录的最小 ContentProvider（薄壳）。
 *
 * <p><b>为什么需要它</b>：targetSdk 24+ 禁止把 {@code file://} URI 交给其他 App
 * （抛 FileUriExposedException），而系统相机的 {@code EXTRA_OUTPUT} 需要一个可写
 * URI。这里用自建 Provider 把 {@code getFilesDir()/ai_photos/} 暴露成
 * {@code content://<pkg>.aiphotos/<name>}，再对相机 Intent 单独授予写权限——
 * 既满足协议，又不引入 FileProvider 的 XML 资源依赖，保持零第三方库。</p>
 *
 * <p><b>安全边界</b>：manifest 中 {@code exported=false} + {@code grantUriPermissions}；
 * 所有路径都经规范路径校验必须落在 {@code ai_photos} 目录内，{@code ..} 与符号链接
 * 逃逸一律拒绝。不支持 query / insert / update / delete，只服务 openFile。</p>
 */
public class AiPhotoProvider extends ContentProvider {

    /** authority 后缀；完整 authority = 包名 + 该后缀。 */
    public static final String AUTHORITY_SUFFIX = ".aiphotos";

    @Override
    public boolean onCreate() {
        return true;
    }

    /** 完整 authority（供构造 URI 用）。 */
    public static String authority(Context context) {
        return context.getPackageName() + AUTHORITY_SUFFIX;
    }

    /** 目标文件 → content URI。 */
    public static Uri uriFor(Context context, File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(file.getName())
                .build();
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File target = resolve(uri);
        if (target == null) throw new FileNotFoundException("非法路径：" + uri);
        int fileMode = ParcelFileDescriptor.MODE_READ_ONLY;
        if ("w".equals(mode) || "wt".equals(mode) || "rw".equals(mode) || "rwt".equals(mode)) {
            fileMode = ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE;
        } else if ("wa".equals(mode)) {
            fileMode = ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_APPEND;
        }
        return ParcelFileDescriptor.open(target, fileMode);
    }

    /**
     * URI 路径段 → 私有目录内的真实文件；越出目录（含 {@code ..}、绝对路径、
     * 符号链接逃逸）返回 null。
     */
    private File resolve(Uri uri) {
        if (uri == null || getContext() == null) return null;
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            return null;
        }
        File base = AiPhotoStore.dir(getContext());
        File target = new File(base, name);
        try {
            String canonicalBase = base.getCanonicalPath();
            String canonicalTarget = target.getCanonicalPath();
            if (!canonicalTarget.startsWith(canonicalBase + File.separator)
                    && !canonicalTarget.equals(canonicalBase)) {
                return null;
            }
            if (!canonicalTarget.contains(AiPhotoStore.DIR_NAME)
                    && !canonicalBase.contains(AiPhotoStore.DIR_NAME)) {
                return null;   // 目录创建失败退回 filesDir 时仍要求路径属于 ai_photos
            }
        } catch (IOException exception) {
            return null;
        }
        return target;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
