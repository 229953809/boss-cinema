package com.fongmi.android.tv.ui.helper;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.bean.TmdbItem;
import com.fongmi.android.tv.service.PersonalRecommendationService;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class TmdbRecommendationRows {

    private TmdbRecommendationRows() {
    }

    public static List<TmdbItem> rankedRelated(@Nullable JsonObject detail, List<TmdbItem> recommendations, List<TmdbItem> similar) {
        return PersonalRecommendationService.rankTmdbItemsForContext(detail, recommendations, similar, Integer.MAX_VALUE);
    }

    public static List<TmdbItem> personalTmdb(List<TmdbItem> personalTmdb, List<TmdbItem> related) {
        List<TmdbItem> items = personalRecommendations(personalTmdb, related, new ArrayList<>(), true);
        return items.isEmpty() ? personalRecommendations(personalTmdb, new ArrayList<>(), new ArrayList<>(), false) : items;
    }

    public static List<TmdbItem> personalDouban(List<TmdbItem> personalDouban, List<TmdbItem> related, List<TmdbItem> personalTmdb) {
        return personalRecommendations(personalDouban, related, personalTmdb, true);
    }

    public static List<TmdbItem> personalAi(List<TmdbItem> personalAi, List<TmdbItem> related, List<TmdbItem> personalTmdb, List<TmdbItem> personalDouban) {
        List<TmdbItem> source = new ArrayList<>(safeList(personalTmdb));
        source.addAll(safeList(personalDouban));
        return personalRecommendations(personalAi, related, source, true);
    }

    private static List<TmdbItem> personalRecommendations(List<TmdbItem> recommendations, List<TmdbItem> related, List<TmdbItem> source, boolean excludeRelated) {
        List<TmdbItem> currentRecommendations = excludeRelated ? safeList(related) : new ArrayList<>();
        List<TmdbItem> sourceRecommendations = safeList(source);
        List<TmdbItem> items = new ArrayList<>();
        for (TmdbItem item : safeList(recommendations)) {
            if (containsRecommendation(currentRecommendations, item) || containsRecommendation(items, item)) continue;
            if (containsRecommendation(sourceRecommendations, item)) continue;
            items.add(item);
        }
        return items;
    }

    private static List<TmdbItem> safeList(List<TmdbItem> items) {
        return items == null ? new ArrayList<>() : items;
    }

    private static boolean containsRecommendation(List<TmdbItem> items, TmdbItem target) {
        if (items == null || target == null) return false;
        for (TmdbItem item : items) if (sameRecommendation(item, target)) return true;
        return false;
    }

    private static boolean sameRecommendation(TmdbItem first, TmdbItem second) {
        if (first == null || second == null) return false;
        if (first.getTmdbId() > 0 && second.getTmdbId() > 0) {
            return first.getTmdbId() == second.getTmdbId() && Objects.equals(first.getMediaType(), second.getMediaType());
        }
        String firstTitle = normalizeTitle(first.getTitle());
        String secondTitle = normalizeTitle(second.getTitle());
        return !firstTitle.isEmpty() && firstTitle.equals(secondTitle);
    }

    private static String normalizeTitle(String text) {
        return text == null || text.isEmpty() ? "" : text.replaceAll("[\\s·•・._\\-/\\\\|()（）\\[\\]【】《》<>:：,，.。]+", "").trim().toLowerCase(Locale.ROOT);
    }
}
