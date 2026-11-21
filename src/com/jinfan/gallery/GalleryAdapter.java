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

    private final List<Object> items;
    private final MediaStoreHelper.ThumbnailLoader loader;
    private final OnPhotoLongPressListener longPressListener;

    public GalleryAdapter(List<Object> items,
                          MediaStoreHelper.ThumbnailLoader loader,
                          OnPhotoLongPressListener listener) {
        this.items = items;
        this.loader = loader;
        this.longPressListener = listener;
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
                    String.format(Locale.getDefault(), "%d 张", header.count));
            return;
        }

        PhotoHolder ph = (PhotoHolder) holder;
        MediaStoreHelper.PhotoEntry entry =
                (MediaStoreHelper.PhotoEntry) items.get(position);

        ph.image.setTag(entry.id);
        bindThumbnail(ph.image, entry.id);

        ph.itemView.setOnLongClickListener(v -> {
            if (longPressListener != null) {
                longPressListener.onPhotoLongPress(ph.image, entry);
            }
            return true;
        });
    }

    private void bindThumbnail(ImageView image, long id) {
        android.graphics.Bitmap cached = loader.getCached(id);
        if (cached != null) {
            image.setImageBitmap(cached);
        } else {
            image.setImageDrawable(null);
            loader.load(image, id);
        }
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

        PhotoHolder(View v) {
            super(v);
            image = v.findViewById(R.id.iv_photo);
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
