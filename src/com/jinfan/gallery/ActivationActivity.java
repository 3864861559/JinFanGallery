package com.jinfan.gallery;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;

/**
 * 首次打开激活引导页：欢迎标题 + 蓝色实心圆 + 白色箭头。
 * 点击箭头后进入 0.3s 开屏页。动画全部为原生属性动画，无动效库。
 */
public class ActivationActivity extends Activity {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_activation);

        View fab = findViewById(R.id.fab_activate);
        final ImageView arrow = findViewById(R.id.iv_arrow);

        fab.setOnClickListener(v -> {
            // 按钮轻微按压回弹
            v.animate()
                    .scaleX(0.92f)
                    .scaleY(0.92f)
                    .setDuration(60)
                    .withEndAction(() -> v.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(80)
                            .start())
                    .start();

            // 箭头轻微摆动，模拟 HyperOS 启动反馈
            arrow.animate()
                    .rotation(12f)
                    .setDuration(120)
                    .withEndAction(() -> arrow.animate()
                            .rotation(0f)
                            .setDuration(120)
                            .start())
                    .start();

            mainHandler.postDelayed(this::enterSplash, 260);
        });
    }

    private void enterSplash() {
        startActivity(new Intent(this, SplashActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
    }
}
