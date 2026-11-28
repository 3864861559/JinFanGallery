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

    /** 一条媒体：图片或视频 */
    public static final class PhotoEntry {
        public final long id;
        public final Uri uri;
        public final long dateTaken;
        public final String dateKey;
        public final boolean video;
        public final long durationMs;

        public PhotoEntry(long id, Uri uri, long dateTaken, String dateKey) {
            this(id, uri, dateTaken, dateKey, false, 0);
        }

        public PhotoEntry(long id, Uri uri, long dateTaken, String dateKey,
                          boolean video, long durationMs) {
            this.id = id;
            this.uri = uri;
            this.dateTaken = dateTaken;
            this.dateKey = dateKey;
            this.video = video;
            this.durationMs = durationMs;
        }

        /** 图片和视频的 _ID 会撞，缓存键要带类型 */
        public String cacheKey() {
            return (video ? "v" : "i") + id;
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
     * 读取全部图片 + 视频元数据，按日期降序组装成 header + 媒体 混合列表。
     * 注意：此方法在 Loader 异步线程中调用。
     */
    public static List<Object> loadItems(Context context) {
        ContentResolver resolver = context.getContentResolver();
        List<PhotoEntry> all = new ArrayList<>();
        all.addAll(queryImages(resolver));
        all.addAll(queryVideos(resolver));
        java.util.Collections.sort(all, (a, b) -> Long.compare(b.dateTaken, a.dateTaken));

        List<Object> items = new ArrayList<>();
        String currentKey = null;
        List<PhotoEntry> group = new ArrayList<>();
        for (PhotoEntry e : all) {
            if (currentKey == null) currentKey = e.dateKey;
            if (!e.dateKey.equals(currentKey)) {
                items.add(new HeaderEntry(labelOf(group.get(0).dateTaken), group.size()));
                items.addAll(group);
                group.clear();
                currentKey = e.dateKey;
            }
            group.add(e);
        }
        if (!group.isEmpty()) {
            items.add(new HeaderEntry(labelOf(group.get(0).dateTaken), group.size()));
            items.addAll(group);
        }
        return items;
    }

    private static List<PhotoEntry> queryImages(ContentResolver resolver) {
        List<PhotoEntry> out = new ArrayList<>();
        Cursor cursor = resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                PROJECTION, null, null,
                MediaStore.Images.Media.DATE_TAKEN + " DESC");
        if (cursor == null) return out;
        try {
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN);
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                long taken = cursor.getLong(takenCol);
                if (taken <= 0) taken = System.currentTimeMillis();
                Uri uri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));
                out.add(new PhotoEntry(id, uri, taken, keyOf(taken)));
            }
        } finally {
            cursor.close();
        }
        return out;
    }

    private static List<PhotoEntry> queryVideos(ContentResolver resolver) {
        List<PhotoEntry> out = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = resolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    new String[]{
                            MediaStore.Video.Media._ID,
                            MediaStore.Video.Media.DATE_MODIFIED,
                            MediaStore.Video.Media.DURATION
                    }, null, null,
                    MediaStore.Video.Media.DATE_MODIFIED + " DESC");
            if (cursor == null) return out;
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int modCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED);
            int durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                long modified = cursor.getLong(modCol) * 1000L;
                if (modified <= 0) modified = System.currentTimeMillis();
                long duration = cursor.getLong(durCol);
                Uri uri = Uri.withAppendedPath(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));
                out.add(new PhotoEntry(id, uri, modified, keyOf(modified), true, duration));
            }
        } catch (Exception ignored) {
            // 老系统或没有视频列时静默降级为只有图片
        } finally {
            if (cursor != null) cursor.close();
        }
        return out;
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

        public Bitmap getCached(PhotoEntry entry) {
            return cache.get(entry.cacheKey());
        }

        public void load(final ImageView target, final PhotoEntry entry) {
            Bitmap cached = getCached(entry);
            if (cached != null) {
                target.setImageBitmap(cached);
                return;
            }
            target.setImageDrawable(null);
            workerHandler.post(() -> {
                final Bitmap b = decodeThumbnail(entry);
                if (b != null) {
                    cache.put(entry.cacheKey(), b);
                }
                mainHandler.post(() -> {
                    if (target != null && b != null) {
                        target.setImageBitmap(b);
                    }
                });
            });
        }

        private Bitmap decodeThumbnail(PhotoEntry entry) {
            long id = entry.id;
            Bitmap thumb;
            if (entry.video) {
                thumb = MediaStore.Video.Thumbnails.getThumbnail(
                        appContext.getContentResolver(), id,
                        MediaStore.Video.Thumbnails.MINI_KIND, null);
                return thumb;
            }
            thumb = MediaStore.Images.Thumbnails.getThumbnail(
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
