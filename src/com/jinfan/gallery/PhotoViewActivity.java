package com.jinfan.gallery;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.InputStream;

/**
 * 全屏看图：短按网格里的照片进入这里。
 * 只按屏幕尺寸降采样解码一次，避免大图 OOM；点击或返回键退出。
 */
public class PhotoViewActivity extends Activity {

    public static final String EXTRA_URI = "extra_uri";

    private ImageView imageView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        imageView = new ImageView(this);
        imageView.setBackgroundColor(0xFF000000);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
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

        final GestureDetector detector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onSingleTapUp(MotionEvent e) { finish(); return true; }
                });
        imageView.setOnTouchListener((v, e) -> detector.onTouchEvent(e));
    }

    /** 只读 bounds 拿尺寸，再按屏幕宽度选 inSampleSize，最后只解码一次。 */
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
