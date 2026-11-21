package com.jinfan.gallery;

import android.app.Activity;
import android.content.Intent;
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

        imageView = new ImageView(this);
        imageView.setBackgroundColor(0xFF000000);
        imageView.setScaleType(ImageView.ScaleType.MATRIX);
        setContentView(imageView);

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
