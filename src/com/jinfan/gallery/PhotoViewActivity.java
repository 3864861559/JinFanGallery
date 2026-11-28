package com.jinfan.gallery;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Build;
import android.view.View;
import android.widget.PopupMenu;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.InputStream;

/**
 * 全屏看图：短按网格里的照片进入这里。
 * 双指放大 / 拖动 / 双击复位；点一下空白或返回键退出。
 * 只按屏幕尺寸降采样解码一次，避免大图 OOM。
 */
public class PhotoViewActivity extends Activity {

    public static final String EXTRA_URI = "extra_uri";

    private ImageView imageView;
    private final Matrix matrix = new Matrix();
    private float scale = 1f;
    private static final float MAX_SCALE = 5f;

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;

    private float lastX, lastY;
    private int width, height;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_photo_view);
        imageView = findViewById(R.id.iv_photo_full);

        String s = getIntent().getStringExtra(EXTRA_URI);
        if (s == null) { finish(); return; }

        Bitmap bmp = decodeSampled(Uri.parse(s));
        if (bmp == null) {
            Toast.makeText(this, "打不开这张照片", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        imageView.setImageBitmap(bmp);
        width = bmp.getWidth();
        height = bmp.getHeight();

        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                float factor = d.getScaleFactor();
                float target = Math.max(1f, Math.min(MAX_SCALE, scale * factor));
                factor = target / scale;
                scale = target;
                matrix.postScale(factor, factor, d.getFocusX(), d.getFocusY());
                clamp();
                imageView.setImageMatrix(matrix);
                return true;
            }
        });

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapUp(MotionEvent e) {
                if (scale <= 1.01f) { finish(); return true; }
                return true; // 放大状态下单击不退出，避免误触
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                scale = 1f;
                fitCenter();
                imageView.setImageMatrix(matrix);
                return true;
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scale <= 1.01f) return false;
                matrix.postTranslate(-dx, -dy);
                clamp();
                imageView.setImageMatrix(matrix);
                return true;
            }
        });

        imageView.post(this::fitCenter);

        findViewById(R.id.act_photo_delete).setOnClickListener(v -> confirmDelete());
        findViewById(R.id.act_photo_more).setOnClickListener(this::showMore);
    }

    /** 底栏：删除 */
    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.photo_delete_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.video_delete, (d, w) -> deletePhoto())
                .show();
    }

    private void deletePhoto() {
        Uri target = Uri.parse(getIntent().getStringExtra(EXTRA_URI));
        try {
            int n = getContentResolver().delete(target, null, null);
            toastAndFinish(n > 0);
        } catch (SecurityException se) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    android.app.RecoverableSecurityException rse =
                            (android.app.RecoverableSecurityException) se;
                    startIntentSenderForResult(
                            rse.getUserAction().getActionIntent().getIntentSender(), 9, null, 0, 0, 0);
                    return;
                } catch (Exception ignored) { }
            }
            toastAndFinish(false);
        } catch (Exception e) {
            toastAndFinish(false);
        }
    }

    private void toastAndFinish(boolean ok) {
        Toast.makeText(this, ok ? R.string.video_delete_done : R.string.video_delete_failed,
                Toast.LENGTH_SHORT).show();
        if (ok) finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 9 && resultCode == RESULT_OK) finish();
    }

    /** 底栏：更多 */
    private void showMore(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.video_details);
        menu.getMenu().add(0, 2, 1, R.string.video_open_with);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) showDetails();
            else openWith();
            return true;
        });
        menu.show();
    }

    private void showDetails() {
        String path = getIntent().getStringExtra(EXTRA_URI);
        String msg = "文件：" + (path == null ? "" : Uri.parse(path).getLastPathSegment())
                + "\n尺寸：" + width + "×" + height;
        new AlertDialog.Builder(this)
                .setTitle(R.string.video_details)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void openWith() {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse(getIntent().getStringExtra(EXTRA_URI)), "image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.video_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /** 初始按屏幕适配，居中显示。 */
    private void fitCenter() {
        int vw = imageView.getWidth(), vh = imageView.getHeight();
        if (vw == 0 || vh == 0 || width == 0 || height == 0) return;
        float s = Math.min(vw / (float) width, vh / (float) height);
        matrix.reset();
        matrix.postScale(s, s);
        matrix.postTranslate((vw - width * s) / 2f, (vh - height * s) / 2f);
        imageView.setImageMatrix(matrix);
        scale = 1f;
    }

    /** 别让图被拖出屏幕外。 */
    private void clamp() {
        android.graphics.RectF r = new android.graphics.RectF(0, 0, width, height);
        matrix.mapRect(r);
        int vw = imageView.getWidth(), vh = imageView.getHeight();
        float dx = 0, dy = 0;
        if (r.width() <= vw) dx = (vw - r.width()) / 2f - r.left;
        else if (r.left > 0) dx = -r.left;
        else if (r.right < vw) dx = vw - r.right;
        if (r.height() <= vh) dy = (vh - r.height()) / 2f - r.top;
        else if (r.top > 0) dy = -r.top;
        else if (r.bottom < vh) dy = vh - r.bottom;
        matrix.postTranslate(dx, dy);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        gestureDetector.onTouchEvent(e);
        return true;
    }

    private Bitmap decodeSampled(Uri uri) {
        try {
            int target = getResources().getDisplayMetrics().widthPixels;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= target) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static Intent intentFor(Activity from, Uri uri) {
        return new Intent(from, PhotoViewActivity.class).putExtra(EXTRA_URI, uri.toString());
    }
}
