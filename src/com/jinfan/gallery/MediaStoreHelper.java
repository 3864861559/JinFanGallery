package com.jinfan.gallery;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.widget.ImageView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * MediaStore 读取与轻量缩略图加载。
 * 低内存原则：
 *  - 游标由 LoaderManager 托管，页面退出立即关闭；
 *  - 缩略图走系统 MediaStore 缓存，不在主线程解码；
 *  - 缓存仅保留屏幕可见范围，不持久化到磁盘。
 */
public final class MediaStoreHelper {

    private MediaStoreHelper() {}

    public static final String[] PROJECTION = new String[] {
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
    };

    /** 照片条目 */
    public static final class PhotoEntry {
        public final long id;
        public final Uri uri;
        public final long dateTaken;
        public final String dateKey;

        public PhotoEntry(long id, Uri uri, long dateTaken, String dateKey) {
            this.id = id;
            this.uri = uri;
            this.dateTaken = dateTaken;
            this.dateKey = dateKey;
        }
    }

    /** 日期分组标题条目 */
    public static final class HeaderEntry {
        public final String label;
        public final int count;

        public HeaderEntry(String label, int count) {
            this.label = label;
            this.count = count;
        }
    }

    private static final SimpleDateFormat KEY_FMT =
            new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private static final SimpleDateFormat LABEL_FMT =
            new SimpleDateFormat("yyyy年MM月dd日", Locale.getDefault());

    public static String keyOf(long dateTaken) {
        return KEY_FMT.format(new Date(dateTaken));
    }

    public static String labelOf(long dateTaken) {
        return LABEL_FMT.format(new Date(dateTaken));
    }

    /**
     * 读取全部图片元数据，按日期降序组装成 header + photo 混合列表。
     * 注意：此方法在 Loader 异步线程中调用。
     */
    public static List<Object> loadItems(Context context) {
        List<Object> items = new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();
        Cursor cursor = resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                PROJECTION, null, null,
                MediaStore.Images.Media.DATE_TAKEN + " DESC");

        if (cursor == null) return items;

        try {
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN);

            String currentKey = null;
            int groupCount = 0;
            List<PhotoEntry> groupPhotos = new ArrayList<>();

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                long taken = cursor.getLong(takenCol);
                if (taken <= 0) taken = System.currentTimeMillis();
                String key = keyOf(taken);

                if (currentKey == null) {
                    currentKey = key;
                }
                if (!key.equals(currentKey)) {
                    items.add(new HeaderEntry(labelOf(groupPhotos.get(0).dateTaken), groupCount));
                    items.addAll(groupPhotos);
                    groupPhotos.clear();
                    groupCount = 0;
                    currentKey = key;
                }

                Uri uri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));
                groupPhotos.add(new PhotoEntry(id, uri, taken, key));
                groupCount++;
            }
            if (!groupPhotos.isEmpty()) {
                items.add(new HeaderEntry(labelOf(groupPhotos.get(0).dateTaken), groupCount));
                items.addAll(groupPhotos);
            }
        } finally {
            cursor.close();
        }
        return items;
    }

    /**
     * 极简缩略图加载器：单后台线程 + 内存 LruCache。
     * 不引入 Glide/Coil，不做磁盘缓存。
     */
    public static final class ThumbnailLoader {
        private final Context appContext;
        private final HandlerThread worker;
        private final Handler workerHandler;
        private final Handler mainHandler;
        private final LruCache<String, Bitmap> cache;

        public ThumbnailLoader(Context context) {
            appContext = context.getApplicationContext();
            worker = new HandlerThread("jinfan-thumb", android.os.Process.THREAD_PRIORITY_BACKGROUND);
            worker.start();
            workerHandler = new Handler(worker.getLooper());
            mainHandler = new Handler(Looper.getMainLooper());

            int maxBytes = (int) (Runtime.getRuntime().maxMemory() / 8);
            cache = new LruCache<String, Bitmap>(maxBytes) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };
        }

        public Bitmap getCached(long id) {
            return cache.get(String.valueOf(id));
        }

        public void load(final ImageView target, final long id) {
            Bitmap cached = getCached(id);
            if (cached != null) {
                target.setImageBitmap(cached);
                return;
            }
            target.setImageDrawable(null);
            workerHandler.post(() -> {
                final Bitmap b = decodeThumbnail(id);
                if (b != null) {
                    cache.put(String.valueOf(id), b);
                }
                mainHandler.post(() -> {
                    if (target != null && b != null) {
                        target.setImageBitmap(b);
                    }
                });
            });
        }

        private Bitmap decodeThumbnail(long id) {
            Bitmap thumb = MediaStore.Images.Thumbnails.getThumbnail(
                    appContext.getContentResolver(), id,
                    MediaStore.Images.Thumbnails.MINI_KIND, null);
            if (thumb != null) return thumb;

            // 降级：直接按小采样率解码文件，避免 OOM
            Cursor c = appContext.getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media.DATA},
                    MediaStore.Images.Media._ID + "=?",
                    new String[]{String.valueOf(id)}, null);
            if (c == null) return null;
            try {
                if (c.moveToFirst()) {
                    String path = c.getString(0);
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(path, opts);
                    opts.inSampleSize = Math.max(1, opts.outHeight / 256);
                    opts.inJustDecodeBounds = false;
                    return ThumbnailUtils.extractThumbnail(
                            BitmapFactory.decodeFile(path, opts), 256, 256);
                }
            } finally {
                c.close();
            }
            return null;
        }

        public void clear() {
            cache.evictAll();
        }

        public void destroy() {
            workerHandler.removeCallbacksAndMessages(null);
            worker.quitSafely();
            clear();
        }
    }
}
