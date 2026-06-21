package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.TmdbPerson;
import com.fongmi.android.tv.utils.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * TMDB 演员横向滚动适配器。
 */
public class TmdbCastAdapter extends RecyclerView.Adapter<TmdbCastAdapter.ViewHolder> {

    private final List<TmdbPerson> items = new ArrayList<>();
    private OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(TmdbPerson person);
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<TmdbPerson> cast) {
        items.clear();
        if (cast != null) items.addAll(cast);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.adapter_tmdb_cast, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(items.get(position), listener);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        private final ImageView profile;
        private final TextView name;
        private final TextView role;

        public ViewHolder(@NonNull android.view.View itemView) {
            super(itemView);
            if (!Util.isLeanback()) {
                itemView.setFocusable(false);
                itemView.setFocusableInTouchMode(false);
            }
            profile = itemView.findViewById(R.id.profile);
            name = itemView.findViewById(R.id.name);
            role = itemView.findViewById(R.id.role);
        }

        void bind(TmdbPerson person, OnItemClickListener listener) {
            name.setText(person.getName());
            role.setText(person.getSubtitle());
            if (person.getProfileUrl() != null && !person.getProfileUrl().isEmpty()) {
                Glide.with(profile.getContext()).load(person.getProfileUrl()).into(profile);
            } else {
                profile.setImageResource(R.color.black);
            }

            if (listener != null) {
                itemView.setOnClickListener(v -> listener.onItemClick(person));
            }
        }
    }
}
