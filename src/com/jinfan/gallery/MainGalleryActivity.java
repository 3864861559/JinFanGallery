package com.jinfan.gallery;

import android.Manifest;
import android.app.Activity;
import android.app.LoaderManager;
import android.content.AsyncTaskLoader;
import android.content.Context;
import android.content.Loader;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 图库主屏：日期分组网格 + 凝光悬浮底栏 + 长按 3D Touch 预览。
 *
 * 性能 / 内存要点：
 *  - 元数据查询走 AsyncTaskLoader，不在主线程 IO；
 *  - RecyclerView 固定尺寸、关闭绘图缓存、开启预取；
 *  - 缩略图后台加载 + LruCache，离开页面立即释放；
 *  - 窗口优先申请 120Hz，设备不支持时自动回退原生刷新率；
 *  - 背景模糊交给 SurfaceFlinger 硬件合成，App 不生成模糊位图。
 */
public class MainGalleryActivity extends Activity
        implements LoaderManager.LoaderCallbacks<List<Object>>,
        GalleryAdapter.OnPhotoLongPressListener {

    private static final int REQ_PERMISSION = 100;
    private static final int LOADER_ID = 1;
    private static final int SPAN_COUNT = 3;

    private RecyclerView recyclerView;
    private GalleryAdapter adapter;
    private final List<Object> items = new ArrayList<>();
    private MediaStoreHelper.ThumbnailLoader thumbLoader;
    private PreviewHelper previewHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        applyHighRefreshRate();
        applyWindowBlur();

        thumbLoader = new MediaStoreHelper.ThumbnailLoader(this);

        recyclerView = findViewById(R.id.rv_gallery);
        FrameLayout previewLayer = findViewById(R.id.preview_layer);
        previewHelper = new PreviewHelper(this, previewLayer);

        findViewById(R.id.btn_more).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, AboutActivity.class)));

        setupRecyclerView();

        if (hasPermission()) {
            getLoaderManager().initLoader(LOADER_ID, null, this);
        } else {
            requestRequiredPermission();
        }
    }

    private void setupRecyclerView() {
        GridLayoutManager layoutManager = new GridLayoutManager(this, SPAN_COUNT);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter != null && adapter.isHeader(position) ? SPAN_COUNT : 1;
            }
        });

        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(24);
        recyclerView.setDrawingCacheEnabled(false);
        recyclerView.setNestedScrollingEnabled(true);
        recyclerView.setScrollingTouchSlop(RecyclerView.TOUCH_SLOP_DEFAULT);

        adapter = new GalleryAdapter(items, thumbLoader, this,
                entry -> startActivity(PhotoViewActivity.intentFor(this, entry.uri)));
        recyclerView.setAdapter(adapter);

        // 首屏预取 6 个 item，滚动更跟手
        if (recyclerView.getLayoutManager() instanceof GridLayoutManager) {
            ((GridLayoutManager) recyclerView.getLayoutManager()).setInitialPrefetchItemCount(SPAN_COUNT * 2);
        }
    }

    /** 向 SurfaceFlinger 申请高刷新率，最终刷新率由显示驱动决定。 */
    private void applyHighRefreshRate() {
        Window window = getWindow();
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.preferredRefreshRate = 120f;
        window.setAttributes(lp);
    }

    /** 系统级背景模糊（API31+）；老版本降级为半透明纯色，不做软件模糊。 */
    private void applyWindowBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Window window = getWindow();
            window.setBackgroundBlurRadius((int) (24 * getResources().getDisplayMetrics().density));
            // 透明窗口背景，让底栏背后的内容参与硬件模糊合成
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }
    }

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestRequiredPermission() {
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        requestPermissions(new String[]{perm}, REQ_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            getLoaderManager().initLoader(LOADER_ID, null, this);
        }
    }

    @Override
    public void onPhotoLongPress(ImageView imageView, MediaStoreHelper.PhotoEntry entry) {
        previewHelper.start(imageView, entry);
    }

    // ---- LoaderManager：异步查询 MediaStore ----

    @Override
    public Loader<List<Object>> onCreateLoader(int id, Bundle args) {
        return new GalleryLoader(this);
    }

    @Override
    public void onLoadFinished(Loader<List<Object>> loader, List<Object> data) {
        items.clear();
        if (data != null) items.addAll(data);
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onLoaderReset(Loader<List<Object>> loader) {
        items.clear();
        adapter.notifyDataSetChanged();
    }

    /** 后台加载相册元数据，结果缓存供配置变化时复用。 */
    private static class GalleryLoader extends AsyncTaskLoader<List<Object>> {
        private List<Object> cache;

        GalleryLoader(Context context) {
            super(context);
        }

        @Override
        public List<Object> loadInBackground() {
            return MediaStoreHelper.loadItems(getContext());
        }

        @Override
        protected void onStartLoading() {
            if (cache != null) {
                deliverResult(cache);
            } else {
                forceLoad();
            }
        }

        @Override
        public void deliverResult(List<Object> data) {
            cache = data;
            super.deliverResult(data);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (previewHelper != null) previewHelper.destroy();
        if (thumbLoader != null) thumbLoader.destroy();
    }
}
