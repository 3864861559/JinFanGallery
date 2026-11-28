package com.jinfan.gallery;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Locale;

/**
 * 日期分组网格适配器。header 占满整行，照片按网格排列。
 * 所有绘制对象在 ViewHolder 构造时复用，onBind 不创建 Paint/Shader。
 */
public class GalleryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_PHOTO = 1;

    public interface OnPhotoLongPressListener {
        void onPhotoLongPress(ImageView imageView, MediaStoreHelper.PhotoEntry entry);
    }

    /** 短按：打开全屏看图。 */
    public interface OnPhotoClickListener {
        void onPhotoClick(MediaStoreHelper.PhotoEntry entry);
    }

    private final List<Object> items;
    private final MediaStoreHelper.ThumbnailLoader loader;
    private final OnPhotoLongPressListener longPressListener;
    private final OnPhotoClickListener clickListener;

    public GalleryAdapter(List<Object> items,
                          MediaStoreHelper.ThumbnailLoader loader,
                          OnPhotoLongPressListener listener,
                          OnPhotoClickListener clickListener) {
        this.items = items;
        this.loader = loader;
        this.longPressListener = listener;
        this.clickListener = clickListener;
        setHasStableIds(true);
    }

    public boolean isHeader(int position) {
        return items.get(position) instanceof MediaStoreHelper.HeaderEntry;
    }

    @Override
    public int getItemViewType(int position) {
        return isHeader(position) ? TYPE_HEADER : TYPE_PHOTO;
    }

    @Override
    public long getItemId(int position) {
        Object o = items.get(position);
        if (o instanceof MediaStoreHelper.HeaderEntry) return -position - 1;
        return ((MediaStoreHelper.PhotoEntry) o).id;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            View v = inflater.inflate(R.layout.item_header, parent, false);
            return new HeaderHolder(v);
        }
        View v = inflater.inflate(R.layout.item_photo, parent, false);
        return new PhotoHolder(v);
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof HeaderHolder) {
            MediaStoreHelper.HeaderEntry header =
                    (MediaStoreHelper.HeaderEntry) items.get(position);
            ((HeaderHolder) holder).date.setText(header.label);
            ((HeaderHolder) holder).count.setText(
                    String.format(Locale.getDefault(), "%d 项", header.count));
            return;
        }

        PhotoHolder ph = (PhotoHolder) holder;
        MediaStoreHelper.PhotoEntry entry =
                (MediaStoreHelper.PhotoEntry) items.get(position);

        ph.image.setTag(entry.cacheKey());
        bindThumbnail(ph.image, entry);

        if (entry.video) {
            ph.playBadge.setVisibility(View.VISIBLE);
            ph.duration.setVisibility(View.VISIBLE);
            ph.duration.setText(formatDuration(entry.durationMs));
        } else {
            ph.playBadge.setVisibility(View.GONE);
            ph.duration.setVisibility(View.GONE);
        }

        ph.itemView.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onPhotoClick(entry);
        });

        ph.itemView.setOnLongClickListener(v -> {
            if (longPressListener != null) {
                longPressListener.onPhotoLongPress(ph.image, entry);
            }
            return true;
        });
    }

    private void bindThumbnail(ImageView image, MediaStoreHelper.PhotoEntry entry) {
        android.graphics.Bitmap cached = loader.getCached(entry);
        if (cached != null) {
            image.setImageBitmap(cached);
        } else {
            image.setImageDrawable(null);
            loader.load(image, entry);
        }
    }

    private static String formatDuration(long ms) {
        long total = Math.max(0, ms) / 1000;
        if (total >= 3600) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d",
                    total / 3600, (total % 3600) / 60, total % 60);
        }
        return String.format(Locale.getDefault(), "%d:%02d", total / 60, total % 60);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView date;
        final TextView count;

        HeaderHolder(View v) {
            super(v);
            date = v.findViewById(R.id.tv_date);
            count = v.findViewById(R.id.tv_count);
        }
    }

    static class PhotoHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final ImageView playBadge;
        final TextView duration;

        PhotoHolder(View v) {
            super(v);
            image = v.findViewById(R.id.iv_photo);
            playBadge = v.findViewById(R.id.iv_play_badge);
            duration = v.findViewById(R.id.tv_duration);
            // GPU 硬件圆角剪裁，不使用 Bitmap 遮罩，降低过度绘制
            image.setClipToOutline(true);
            image.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    float r = 12 * view.getResources().getDisplayMetrics().density;
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                }
            });
        }
    }
}
