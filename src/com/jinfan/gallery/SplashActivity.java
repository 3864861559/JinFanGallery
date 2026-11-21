package com.jinfan.gallery;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * 开屏页：固定展示金凡照片 0.3 秒后进入图库主屏。
 * 独立 Activity 精准控制时长，不受系统 SplashScreen API 调度限制。
 */
public class SplashActivity extends Activity {

    private static final long SPLASH_DURATION = 300L; // 0.3s
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 欢迎页只显示一次：第二次启动直接进图库。
        android.content.SharedPreferences sp = getSharedPreferences("launch", MODE_PRIVATE);
        if (sp.getBoolean("welcomed", false)) {
            startActivity(new Intent(this, MainGalleryActivity.class));
            overridePendingTransition(0, 0);
            finish();
            return;
        }
        sp.edit().putBoolean("welcomed", true).apply();
        setContentView(R.layout.activity_splash);

        mainHandler.postDelayed(() -> {
            startActivity(new Intent(this, MainGalleryActivity.class));
            overridePendingTransition(0, 0);
            finish();
        }, SPLASH_DURATION);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
    }
}
