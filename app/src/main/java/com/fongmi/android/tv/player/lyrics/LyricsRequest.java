package com.fongmi.android.tv.player.lyrics;

import android.net.Uri;
import android.text.TextUtils;

import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;

import com.fongmi.android.tv.player.PlayerManager;
import com.github.catvod.utils.Util;

import java.io.File;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class LyricsRequest {

    private final String key;
    private final String url;
    private final String title;
    private final String artist;
    private final String album;
    private final long durationMs;

    public LyricsRequest(String key, String url, String title, String artist, String album, long durationMs) {
        this.key = clean(key);
        this.url = clean(url);
        this.title = cleanTitle(title, url);
        this.artist = cleanArtist(artist);
        this.album = clean(album);
        this.durationMs = durationMs > 0 && durationMs != C.TIME_UNSET ? durationMs : 0;
    }

    public static LyricsRequest from(PlayerManager player) {
        MediaMetadata metadata = player.getMetadata();
        String title = metadata == null || metadata.title == null ? "" : metadata.title.toString();
        String artist = metadata == null || metadata.artist == null ? "" : metadata.artist.toString();
        String url = player.getUrl();
        String name = cleanTitle(title, url);
        String singer = cleanArtist(artist);
        String[] split = splitArtistTitle(name);
        if (TextUtils.isEmpty(singer) && split != null) {
            singer = split[0];
            name = split[1];
        }
        singer = normalizeArtistFromEpisode(name, singer);
        return new LyricsRequest(player.getKey(), url, name, singer, "", player.getDuration());
    }

    public LyricsRequest withKeyword(String keyword) {
        String value = cleanTitle(keyword, url);
        if (TextUtils.isEmpty(value)) return this;
        String name = value;
        String singer = "";
        String[] split = splitArtistTitle(value);
        if (split != null) {
            singer = split[0];
            name = split[1];
        }
        return new LyricsRequest(key, url, name, singer, album, durationMs);
    }

    public String displayKeyword() {
        if (TextUtils.isEmpty(artist)) return title;
        return artist + " - " + title;
    }

    public List<String> searchKeywords() {
        List<String> keywords = new ArrayList<>();
        String name = clean(title);
        String singer = clean(artist);
        addKeyword(keywords, singer, " - ", name);
        addKeyword(keywords, name, " - ", singer);
        addKeyword(keywords, singer, " ", name);
        addKeyword(keywords, name, " ", singer);
        addKeyword(keywords, name);
        addKeyword(keywords, stripSearchNoise(name));
        addKeyword(keywords, normalizeSearchText(name));
        for (String artist : splitArtists(singer)) addKeyword(keywords, artist, " ", name);
        for (String title : splitTitleAliases(name)) {
            addKeyword(keywords, singer, " ", title);
            addKeyword(keywords, title);
        }
        return keywords;
    }

    public String getKey() {
        return key;
    }

    public String getUrl() {
        return url;
    }

    public String getTitle() {
        return title;
    }

    public String getArtist() {
        return artist;
    }

    public String getAlbum() {
        return album;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public int getDurationSec() {
        return durationMs <= 0 ? 0 : (int) Math.round(durationMs / 1000.0);
    }

    public boolean isValid() {
        return !TextUtils.isEmpty(title);
    }

    public String signature() {
        return Util.md5(String.join("|", key, url, title, artist, album, String.valueOf(getDurationSec())));
    }

    private static String clean(String text) {
        return Objects.toString(text, "").trim();
    }

    private static void addKeyword(List<String> keywords, String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            String value = clean(part);
            if (TextUtils.isEmpty(value)) return;
            builder.append(value);
        }
        String keyword = builder.toString().replaceAll("\\s+", " ").trim();
        if (!TextUtils.isEmpty(keyword) && !containsKeyword(keywords, keyword)) keywords.add(keyword);
    }

    private static boolean containsKeyword(List<String> keywords, String keyword) {
        String normalized = normalizeSearchText(keyword);
        for (String item : keywords) if (normalizeSearchText(item).equals(normalized)) return true;
        return false;
    }

    private static String stripSearchNoise(String text) {
        String value = clean(text);
        value = value.replaceAll("\\([^)]*\\)|\\[[^]]*]|（[^）]*）|【[^】]*】", " ");
        value = value.replaceAll("(?i)\\b(tv size|short ver\\.?|full ver\\.?|instrumental|karaoke|off vocal|cover|remix|live|opening|ending|op|ed)\\b", " ");
        value = value.replaceAll("(?i)tvアニメ|テレビアニメ|アニメ|オープニング|エンディング|主題歌|挿入歌", " ");
        return value.replaceAll("\\s+", " ").trim();
    }

    private static List<String> splitArtists(String artist) {
        List<String> values = new ArrayList<>();
        for (String item : clean(artist).split("(?i)\\s*(?:/|,|、|&|feat\\.?|featuring|with|;|；)\\s*")) {
            String value = normalizeSearchText(item);
            if (!TextUtils.isEmpty(value) && !containsKeyword(values, value)) values.add(value);
        }
        return values;
    }

    private static List<String> splitTitleAliases(String title) {
        List<String> values = new ArrayList<>();
        for (String item : clean(title).split("\\s*(?:/|／|,|、|;|；)\\s*")) {
            String value = normalizeSearchText(item);
            if (!TextUtils.isEmpty(value) && !containsKeyword(values, value)) values.add(value);
        }
        return values;
    }

    private static String normalizeSearchText(String text) {
        return Normalizer.normalize(stripSearchNoise(text), Normalizer.Form.NFKC)
                .replace('　', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String cleanArtist(String text) {
        String value = clean(text);
        if (value.equalsIgnoreCase("unknown artist")) return "";
        if (value.matches(".*第\\s*\\d+\\s*[集期话話].*")) return "";
        return value;
    }

    private static String cleanTitle(String title, String url) {
        String value = clean(title);
        if (TextUtils.isEmpty(value) || value.startsWith("http://") || value.startsWith("https://") || value.startsWith("file://")) value = fileName(url);
        if (TextUtils.isEmpty(value)) value = fileName(title);
        return stripExtension(value);
    }

    private static String fileName(String url) {
        try {
            if (TextUtils.isEmpty(url)) return "";
            Uri uri = Uri.parse(url);
            String path = uri.getPath();
            String name = TextUtils.isEmpty(path) ? url : new File(path).getName();
            return Uri.decode(name);
        } catch (Exception e) {
            return "";
        }
    }

    private static String stripExtension(String name) {
        String value = clean(name);
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        int dot = value.lastIndexOf('.');
        if (dot > 0 && value.length() - dot <= 6) value = value.substring(0, dot);
        return value.trim();
    }

    private static String[] splitArtistTitle(String title) {
        String value = clean(title);
        String[] separators = new String[]{" - ", " – ", " — ", "_-_"};
        for (String separator : separators) {
            int index = value.indexOf(separator);
            if (index <= 0 || index >= value.length() - separator.length()) continue;
            String artist = value.substring(0, index).trim();
            String name = value.substring(index + separator.length()).trim();
            if (!artist.isEmpty() && !name.isEmpty()) return new String[]{artist, name};
        }
        int hyphen = value.indexOf('-');
        if (hyphen > 0 && hyphen == value.lastIndexOf('-') && hyphen < value.length() - 1) {
            String artist = value.substring(0, hyphen).trim();
            String name = value.substring(hyphen + 1).trim();
            if (artist.length() >= 2 && name.length() >= 2) return new String[]{artist, name};
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.endsWith(" official audio") || lower.endsWith(" official video")) return null;
        return null;
    }

    private static String normalizeArtistFromEpisode(String title, String artist) {
        String name = clean(title);
        String value = cleanArtist(artist);
        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(value)) return value;
        for (String separator : new String[]{" - ", " – ", " — ", "-"}) {
            if (value.startsWith(name + separator) && value.length() > name.length() + separator.length()) {
                return value.substring(name.length() + separator.length()).trim();
            }
            if (value.endsWith(separator + name) && value.length() > name.length() + separator.length()) {
                return value.substring(0, value.length() - name.length() - separator.length()).trim();
            }
        }
        return value;
    }
}
