package com.jinfan.gallery;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

/**
 * OPPO 凝光视效风格悬浮底栏（低内存实现）。
 *
 * 三层材质，全部由原生渲染管线完成，不生成离屏模糊 Bitmap：
 *  1. 基底：半透明亚克力纯色（深浅色自动切换）；
 *  2. 背景模糊：由 Activity 窗口的 SurfaceFlinger 硬件模糊提供，本 View 不做截图模糊；
 *  3. 高光：左上角 45° 斜向打入的一条克制柔光，Shader 仅在尺寸变化时创建一次。
 */
public class NingGuangFloatingBar extends FrameLayout {

    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient lightGradient;
    private boolean isDark;

    private final float radius;
    private final float density;

    private final BroadcastReceiver uiModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateColors();
            postInvalidate();
        }
    };

    public NingGuangFloatingBar(Context context) {
        this(context, null);
    }

    public NingGuangFloatingBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 24 * density;
        init();
    }

    private void init() {
        setWillNotDraw(false);
        highlightPaint.setStrokeWidth(1.6f * density);
        highlightPaint.setStrokeCap(Paint.Cap.ROUND);

        // 硬件圆角剪裁，不走 Bitmap 遮罩
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, getWidth(), getHeight(), radius);
            }
        });
        setClipToOutline(true);

        buildIconRow();
        updateColors();
    }

    private void buildIconRow() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(24 * density, 0, 24 * density, 0);

        ImageView photos = new ImageView(getContext());
        photos.setImageResource(R.drawable.ic_photo);
        photos.setContentDescription(getContext().getString(R.string.photos));
        applyIcon(photos, true);

        ImageView albums = new ImageView(getContext());
        albums.setImageResource(R.drawable.ic_album);
        albums.setContentDescription(getContext().getString(R.string.albums));
        applyIcon(albums, false);

        LinearLayout.LayoutParams ip =
                new LinearLayout.LayoutParams((int) (28 * density), (int) (28 * density));
        ip.leftMargin = (int) (28 * density);
        ip.rightMargin = (int) (28 * density);
        row.addView(photos);
        row.addView(albums);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.CENTER);
        addView(row, lp);
    }

    private void applyIcon(ImageView icon, boolean selected) {
        int size = (int) (28 * density);
        icon.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        icon.setClickable(true);
        icon.setFocusable(true);
        icon.setSelected(selected);
        icon.setColorFilter(getResources().getColor(R.color.glass_icon));
    }

    private void updateColors() {
        int uiMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        isDark = uiMode == Configuration.UI_MODE_NIGHT_YES;
        basePaint.setColor(getResources().getColor(
                isDark ? R.color.glass_base : R.color.glass_base));
        rebuildGradientIfNeeded();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuildGradientIfNeeded();
    }

    /** 45° 斜向柔光：从左上角打入，向右下衰减；仅在尺寸/配色变化时重建。 */
    private void rebuildGradientIfNeeded() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        int startColor = isDark ? 0x26FFFFFF : 0x35FFFFFF;
        lightGradient = new LinearGradient(
                0f, 0f, w * 0.45f, h * 0.45f,
                startColor, 0x00FFFFFF, Shader.TileMode.CLAMP);
        highlightPaint.setShader(lightGradient);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        canvas.drawRoundRect(0, 0, w, h, radius, radius, basePaint);
        // 左上 45° 斜向高光
        canvas.drawLine(
                2 * density, 1f,
                w * 0.45f, h * 0.45f,
                highlightPaint);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        IntentFilter filter = new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED);
        getContext().registerReceiver(uiModeReceiver, filter);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getContext().unregisterReceiver(uiModeReceiver);
    }
}
