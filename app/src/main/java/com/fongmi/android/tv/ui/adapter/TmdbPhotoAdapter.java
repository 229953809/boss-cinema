package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
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
 * TMDB 剧照横向滚动适配器。
 */
public class TmdbPhotoAdapter extends RecyclerView.Adapter<TmdbPhotoAdapter.ViewHolder> {

    private final List<String> items = new ArrayList<>();
    private OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(String url, int position);
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<String> photos) {
        items.clear();
        if (photos != null) items.addAll(photos);
        notifyDataSetChanged();
    }

    public List<String> getItems() {
        return new ArrayList<>(items);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.adapter_tmdb_photo, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(items.get(position), position, listener);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        private final ImageView photo;

        public ViewHolder(@NonNull android.view.View itemView) {
            super(itemView);
            if (!Util.isLeanback()) {
                itemView.setFocusable(false);
                itemView.setFocusableInTouchMode(false);
            }
            photo = itemView.findViewById(R.id.photo);
        }

        void bind(String url, int position, OnItemClickListener listener) {
            if (url != null && !url.isEmpty()) {
                Glide.with(photo.getContext()).load(url).into(photo);
            } else {
                photo.setImageResource(R.color.black);
            }

            if (listener != null) {
                itemView.setOnClickListener(v -> listener.onItemClick(url, position));
            }
        }
    }
}
