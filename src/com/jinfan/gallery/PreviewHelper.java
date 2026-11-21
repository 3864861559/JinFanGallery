package com.jinfan.gallery;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.view.animation.AccelerateDecelerateInterpolator;

/**
 * 3D Touch 长按预览 + ColorOS 抛物线归位。
 *
 * 低内存约束：
 *  - 预览图直接复用网格已加载的缩略图 Bitmap，不重新解码、不加载原图；
 *  - 不做全屏背景模糊截图，背景仅一层纯色变暗；
 *  - 跟手只修改 RenderNode 的 X/Y/Alpha/Scale，不触发 onDraw 重绘；
 *  - 动画结束立即移除预览 View 并释放引用。
 */
public class PreviewHelper {

    private final Activity activity;
    private final FrameLayout layer;
    private final float density;
    private final float screenWidth;
    private final float screenHeight;

    private ImageView preview;
    private View scrim;
    private View source;
    private final int[] sourceLocation = new int[2];
    private float previewSize;
    private boolean previewing;

    public PreviewHelper(Activity activity, FrameLayout layer) {
        this.activity = activity;
        this.layer = layer;
        this.density = activity.getResources().getDisplayMetrics().density;
        this.screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        this.screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
    }

    public boolean isPreviewing() {
        return previewing;
    }

    public void start(ImageView sourceView, MediaStoreHelper.PhotoEntry entry) {
        if (previewing) return;

        Drawable drawable = sourceView.getDrawable();
        if (!(drawable instanceof BitmapDrawable)) return;
        Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
        if (bitmap == null) return;

        this.source = sourceView;
        sourceView.getLocationOnScreen(sourceLocation);

        int srcW = sourceView.getWidth();
        int srcH = sourceView.getHeight();
        previewSize = Math.min(srcW, srcH) * 1.12f;
        float minSize = 240 * density;
        if (previewSize < minSize) previewSize = minSize;

        // 背景仅纯色压暗，不做模糊截图
        scrim = new View(activity);
        scrim.setBackgroundColor(activity.getResources().getColor(R.color.scrim));
        scrim.setAlpha(0f);
        layer.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        scrim.animate().alpha(0.4f).setDuration(120).start();

        preview = new ImageView(activity);
        preview.setImageBitmap(bitmap);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setClipToOutline(true);
        preview.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 16 * density);
            }
        });
        preview.setX(sourceLocation[0]);
        preview.setY(sourceLocation[1]);
        preview.setScaleX(0.9f);
        preview.setScaleY(0.9f);
        preview.setAlpha(0f);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                (int) previewSize, (int) previewSize);
        layer.addView(preview, lp);

        preview.animate()
                .scaleX(1.12f)
                .scaleY(1.12f)
                .alpha(1f)
                .x((screenWidth - previewSize) / 2f)
                .y((screenHeight - previewSize) / 2f)
                .setDuration(120)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();

        previewing = true;
        layer.setOnTouchListener(this::onLayerTouch);
    }

    private boolean onLayerTouch(View v, MotionEvent event) {
        if (preview == null) return false;
        switch (event.getAction()) {
            case MotionEvent.ACTION_MOVE:
                // 跟手：仅移动 RenderNode 变换矩阵，不重绘
                preview.setX(event.getRawX() - previewSize / 2f);
                preview.setY(event.getRawY() - previewSize / 2f);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                finish();
                return true;
            default:
                return false;
        }
    }

    /** 抬起后沿抛物线回到原缩略图位置。 */
    private void finish() {
        if (preview == null) return;

        final float endX = sourceLocation[0];
        final float endY = sourceLocation[1];
        final float startY = preview.getY();
        final float toss = 120 * density;

        preview.animate()
                .x(endX)
                .scaleX(0.4f)
                .scaleY(0.4f)
                .alpha(0f)
                .setDuration(240)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(this::release)
                .start();

        // Y 轴独立做上抛 -> 回落的抛物线轨迹
        ObjectAnimator yAnim = ObjectAnimator.ofFloat(
                preview, "y", startY, startY - toss, endY);
        yAnim.setDuration(240);
        yAnim.setInterpolator(new AccelerateDecelerateInterpolator());
        yAnim.start();

        if (scrim != null) {
            scrim.animate().alpha(0f).setDuration(200).start();
        }
    }

    private void release() {
        if (preview != null) {
            layer.removeView(preview);
            preview.setImageBitmap(null);
            preview = null;
        }
        if (scrim != null) {
            layer.removeView(scrim);
            scrim = null;
        }
        source = null;
        previewing = false;
        layer.setOnTouchListener(null);
    }

    /** 页面退出时兜底释放。 */
    public void destroy() {
        if (previewing) release();
    }
}
