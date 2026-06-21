package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * TMDB 人物照片列表适配器
 */
public class TmdbPersonPhotoAdapter extends RecyclerView.Adapter<TmdbPersonPhotoAdapter.ViewHolder> {

    public interface Listener {
        void onItemClick(int position, String url);
    }

    private final List<String> items = new ArrayList<>();
    private final Listener listener;

    public TmdbPersonPhotoAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<String> values) {
        items.clear();
        if (values != null) items.addAll(values);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tmdb_person_photo, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String url = items.get(position);
        Glide.with(holder.photo.getContext())
                .load(url)
                .override(320, 480)   // 限制加载尺寸
                .thumbnail(0.1f)      // 缩略图
                .dontAnimate()        // 禁用动画
                .into(holder.photo);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onItemClick(position, url);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView photo;

        ViewHolder(View view) {
            super(view);
            if (!Util.isLeanback()) {
                itemView.setFocusable(false);
                itemView.setFocusableInTouchMode(false);
            }
            photo = view.findViewById(R.id.photo);
        }
    }
}
