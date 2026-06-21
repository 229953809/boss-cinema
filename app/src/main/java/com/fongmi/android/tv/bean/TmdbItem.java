package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import java.io.Serializable;

public class TmdbItem implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int tmdbId;
    private final String mediaType;
    private final String title;
    private final String subtitle;
    private final String overview;
    private final String posterUrl;
    private final String backdropUrl;
    private final String credit;
    private final double rating;
    private final String originalLanguage;
    private final String originCountry;

    public TmdbItem(int tmdbId, String mediaType, String title, String subtitle, String overview, String posterUrl, String backdropUrl) {
        this(tmdbId, mediaType, title, subtitle, overview, posterUrl, backdropUrl, "", 0.0);
    }

    public TmdbItem(int tmdbId, String mediaType, String title, String subtitle, String overview, String posterUrl, String backdropUrl, String credit) {
        this(tmdbId, mediaType, title, subtitle, overview, posterUrl, backdropUrl, credit, 0.0);
    }

    public TmdbItem(int tmdbId, String mediaType, String title, String subtitle, String overview, String posterUrl, String backdropUrl, String credit, double rating) {
        this(tmdbId, mediaType, title, subtitle, overview, posterUrl, backdropUrl, credit, rating, "", "");
    }

    public TmdbItem(int tmdbId, String mediaType, String title, String subtitle, String overview, String posterUrl, String backdropUrl, String credit, double rating, String originalLanguage, String originCountry) {
        this.tmdbId = tmdbId;
        this.mediaType = mediaType;
        this.title = title;
        this.subtitle = subtitle;
        this.overview = overview;
        this.posterUrl = posterUrl;
        this.backdropUrl = backdropUrl;
        this.credit = credit;
        this.rating = rating;
        this.originalLanguage = originalLanguage;
        this.originCountry = originCountry;
    }

    public int getTmdbId() {
        return tmdbId;
    }

    public String getMediaType() {
        return TextUtils.isEmpty(mediaType) ? "" : mediaType;
    }

    public String getTitle() {
        return TextUtils.isEmpty(title) ? "" : title;
    }

    public String getSubtitle() {
        return TextUtils.isEmpty(subtitle) ? "" : subtitle;
    }

    public String getOverview() {
        return TextUtils.isEmpty(overview) ? "" : overview;
    }

    public String getPosterUrl() {
        return TextUtils.isEmpty(posterUrl) ? "" : posterUrl;
    }

    public String getBackdropUrl() {
        return TextUtils.isEmpty(backdropUrl) ? "" : backdropUrl;
    }

    public String getCredit() {
        return TextUtils.isEmpty(credit) ? "" : credit;
    }

    public double getRating() {
        return rating;
    }

    public String getOriginalLanguage() {
        return TextUtils.isEmpty(originalLanguage) ? "" : originalLanguage;
    }

    public String getOriginCountry() {
        return TextUtils.isEmpty(originCountry) ? "" : originCountry;
    }

    public boolean isTv() {
        return "tv".equals(mediaType);
    }

    public boolean isMovie() {
        return "movie".equals(mediaType);
    }
}
