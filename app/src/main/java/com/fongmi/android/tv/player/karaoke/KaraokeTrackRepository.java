package com.fongmi.android.tv.player.karaoke;

import android.text.Html;
import android.net.Uri;
import android.text.TextUtils;
import android.webkit.CookieManager;

import androidx.media3.common.MediaMetadata;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class KaraokeTrackRepository {

    static final long MAX_REMOTE_BYTES = 512L * 1024L;
    private static final long MAX_SIDECAR_BYTES = 512L * 1024L;
    private static final long MAX_MIDI_BYTES = 2L * 1024L * 1024L;
    private static final long SEARCH_CACHE_MS = TimeUnit.MINUTES.toMillis(10);
    private static final Pattern USDB_ID = Pattern.compile("(?i)(?:usdb\\.animux\\.de[\\s\\S]*?[?&]id=|\\busdb\\s*[:#]?\\s*|^)(\\d{3,6})(?:\\D|$)");
    private static final Pattern USDB_FIELD = Pattern.compile("(?is)<tr\\s+class=\"list_tr[12]\"\\s*>\\s*<td>\\s*%s\\s*</td>\\s*<td>(.*?)</td>");
    private static final Pattern USDB_NOTE = Pattern.compile("giveinfo0\\('([^']*)','(-?\\d+)','(-?\\d+)','(-?\\d+)','([^']*)','([^']*)'\\)");
    private static final Pattern GITHUB_BLOB = Pattern.compile("(?i)^https://github\\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+)$");
    private static final Map<String, SearchCache> SEARCH_CACHE = new HashMap<>();
    private static final KaraokeTrackProvider[] PROVIDERS = new KaraokeTrackProvider[]{
            new KaraokeGithubTrackProvider(),
            new KaraokeUltraStarEsProvider(),
            new KaraokeUsdbProvider()
    };
    private static final OkHttpClient CLIENT = OkHttp.client()
            .newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    public void load(PlayerManager player, Consumer<KaraokeTrack> callback) {
        String url = player == null ? null : player.getUrl();
        String title = getTitle(player);
        String signature = signatureOf(player);
        Task.execute(() -> {
            KaraokeTrack track = load(url, title, signature);
            if (callback != null) App.post(() -> callback.accept(track));
        });
    }

    public KaraokeTrack load(String url, String title) {
        return load(url, title, null);
    }

    public KaraokeTrack load(String url, String title, String signature) {
        KaraokeTrack bound = readTrack(boundFile(signature));
        if (bound != null && bound.hasScoredNotes()) return bound;
        File audio = resolveLocalFile(url);
        if (audio == null || !audio.isFile()) return null;
        for (File candidate : candidates(audio, title)) {
            KaraokeTrack track = readTrack(candidate);
            if (track != null && track.hasScoredNotes()) return track;
        }
        return null;
    }

    public static ImportResult importText(PlayerManager player, String name, String text) {
        if (player == null || player.isEmpty()) return ImportResult.fail("empty player");
        return importText(signatureOf(player), name, text);
    }

    public static ImportResult importGenerated(PlayerManager player, List<LyricsLine> lines) {
        if (player == null || player.isEmpty()) return ImportResult.fail("empty player");
        try {
            String text = KaraokeGeneratedTrackBuilder.build(defaultKeyword(player), getArtist(player), lines, player.getDuration());
            return importText(signatureOf(player), "Generated rhythm scoring track", text);
        } catch (Exception e) {
            return ImportResult.fail(e.getMessage());
        }
    }

    public static boolean canGenerate(List<LyricsLine> lines) {
        return KaraokeGeneratedTrackBuilder.canGenerate(lines);
    }

    public static ImportResult importFile(PlayerManager player, File file) {
        if (player == null || player.isEmpty()) return ImportResult.fail("empty player");
        if (file == null || !file.isFile()) return ImportResult.fail("empty file");
        try {
            byte[] bytes = readBytes(file, MAX_MIDI_BYTES);
            String text = MidiKaraokeParser.looksLikeMidi(bytes)
                    ? MidiKaraokeParser.toUltraStar(file.getName(), bytes)
                    : readText(file);
            return importText(signatureOf(player), file.getName(), text);
        } catch (Exception e) {
            return ImportResult.fail(e.getMessage());
        }
    }

    public static ImportResult importText(String signature, String name, String text) {
        try {
            if (TextUtils.isEmpty(signature)) return ImportResult.fail("empty signature");
            if (TextUtils.isEmpty(text)) return ImportResult.fail("empty track");
            if (text.getBytes(StandardCharsets.UTF_8).length > MAX_SIDECAR_BYTES) return ImportResult.fail("track too large");
            if (!UltraStarParser.looksLikeUltraStar(text)) return ImportResult.fail("not ultrastar");
            KaraokeTrack track = UltraStarParser.parse(text);
            if (track == null || !track.hasScoredNotes()) return ImportResult.fail("no scored notes");
            Path.write(boundFile(signature), normalizeText(text).getBytes(StandardCharsets.UTF_8));
            return ImportResult.success(track, name);
        } catch (Exception e) {
            return ImportResult.fail(e.getMessage());
        }
    }

    public static void importUrl(PlayerManager player, String url, Consumer<ImportResult> callback) {
        String signature = signatureOf(player);
        Task.execute(() -> {
            ImportResult result;
            try {
                result = importText(signature, url, getTrackText(url));
            } catch (Throwable e) {
                result = ImportResult.fail(e.getMessage());
            }
            ImportResult finalResult = result;
            if (callback != null) App.post(() -> callback.accept(finalResult));
        });
    }

    public static void search(PlayerManager player, String keyword, Consumer<List<SearchResult>> callback) {
        String query = TextUtils.isEmpty(keyword) ? defaultKeyword(player) : keyword.trim();
        String cacheKey = cacheKey(query);
        List<SearchResult> cached = getSearchCache(cacheKey);
        if (cached != null) {
            if (callback != null) App.post(() -> callback.accept(cached));
            return;
        }
        Task.execute(() -> {
            List<SearchResult> results = new ArrayList<>();
            for (KaraokeTrackProvider provider : PROVIDERS) {
                try {
                    addUnique(results, provider.search(query));
                } catch (Exception ignored) {
                }
            }
            putSearchCache(cacheKey, results);
            if (callback != null) App.post(() -> callback.accept(results));
        });
    }

    public static String defaultKeyword(PlayerManager player) {
        String title = getTitle(player);
        if (!TextUtils.isEmpty(title)) return stripExtension(title);
        if (player == null) return "";
        return stripExtension(player.getKey());
    }

    public static boolean hasBinding(PlayerManager player) {
        File file = boundFile(signatureOf(player));
        return file != null && file.isFile() && file.length() > 0;
    }

    public static boolean clearBinding(PlayerManager player) {
        File file = boundFile(signatureOf(player));
        return file != null && file.exists() && file.delete();
    }

    private static KaraokeTrack readTrack(File file) {
        try {
            if (file == null || !file.isFile() || file.length() <= 0 || file.length() > MAX_SIDECAR_BYTES) return null;
            String text = readText(file);
            if (!UltraStarParser.looksLikeUltraStar(text)) return null;
            KaraokeTrack track = UltraStarParser.parse(text);
            return track.hasScoredNotes() ? track : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String getTrackText(String url) throws Exception {
        RemoteUrl remote = RemoteUrl.of(url);
        String usdbId = parseUsdbId(remote.url);
        if (!TextUtils.isEmpty(usdbId)) return getUsdbTrackText(usdbId, remote.cookie);
        return getRemoteText(remote.url, remote.cookie);
    }

    static String getRemoteText(String url, String cookie) throws Exception {
        if (!isHttpUrl(url)) throw new IllegalArgumentException("invalid url");
        Request.Builder builder = new Request.Builder().url(url.trim()).header("User-Agent", userAgent());
        String actualCookie = !TextUtils.isEmpty(cookie) ? cookie : webCookie(url);
        if (!TextUtils.isEmpty(actualCookie)) builder.header("Cookie", actualCookie);
        Request request = builder.build();
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IllegalStateException("http " + response.code());
            long length = response.body().contentLength();
            if (length > MAX_REMOTE_BYTES) throw new IllegalStateException("track too large");
            String type = response.header("Content-Type", "");
            String text = response.body().string();
            if (text.getBytes(StandardCharsets.UTF_8).length > MAX_REMOTE_BYTES) throw new IllegalStateException("track too large");
            String lowerType = type.toLowerCase(Locale.ROOT);
            boolean textual = lowerType.contains("text") || lowerType.contains("html") || lowerType.contains("xml") || lowerType.contains("json");
            if (!TextUtils.isEmpty(type) && !textual && !UltraStarParser.looksLikeUltraStar(text)) throw new IllegalStateException("not text");
            return text;
        }
    }

    private static String getUsdbTrackText(String id, String cookie) throws Exception {
        String detail = getRemoteText("https://usdb.animux.de/?link=detail&id=" + id, cookie);
        String bpm = parseDetailField(detail, "BPM");
        String gap = parseDetailField(detail, "GAP");
        if (TextUtils.isEmpty(bpm) || TextUtils.isEmpty(gap)) throw new IllegalStateException("missing USDB timing");
        String title = parseTitle(detail);
        String notes = getRemoteText("https://usdb.animux.de/view.php?id=" + id + "&database1=deluxe_songs", cookie);
        Matcher matcher = USDB_NOTE.matcher(notes);
        StringBuilder builder = new StringBuilder();
        builder.append("#TITLE:").append(parseSong(title)).append('\n');
        builder.append("#ARTIST:").append(parseArtist(title)).append('\n');
        builder.append("#BPM:").append(bpm).append('\n');
        builder.append("#GAP:").append(gap).append('\n');
        int count = 0;
        while (matcher.find()) {
            String type = matcher.group(1);
            String start = matcher.group(2);
            String length = matcher.group(3);
            String pitch = matcher.group(4);
            String lyric = html(matcher.group(6)).replace('\n', ' ').trim();
            builder.append(usdbPrefix(type)).append(' ')
                    .append(start).append(' ')
                    .append(length).append(' ')
                    .append(pitch).append(' ')
                    .append(lyric).append('\n');
            count++;
        }
        if (count == 0) throw new IllegalStateException("no USDB notes");
        builder.append('E').append('\n');
        return builder.toString();
    }

    static String parseUsdbId(String text) {
        if (TextUtils.isEmpty(text)) return "";
        Matcher matcher = USDB_ID.matcher(text.trim());
        return matcher.find() ? matcher.group(1) : "";
    }

    static String parseDetailField(String html, String field) {
        Matcher matcher = Pattern.compile(String.format(Locale.US, USDB_FIELD.pattern(), Pattern.quote(field))).matcher(html);
        return matcher.find() ? html(matcher.group(1)).trim() : "";
    }

    static String parseTitle(String html) {
        String title = find(Pattern.compile("(?is)<title>\\s*USDB\\s*-\\s*(.*?)\\s*</title>"), html, 1);
        if (!TextUtils.isEmpty(title)) return html(title);
        return html(find(Pattern.compile("(?is)<th[^>]*>\\s*<span[^>]*>\\s*<b>(.*?)</b>"), html, 1));
    }

    static String parseArtist(String value) {
        String text = value == null ? "" : value.trim();
        int index = text.indexOf(" - ");
        return index > 0 ? text.substring(0, index).trim() : "";
    }

    static String parseSong(String value) {
        String text = value == null ? "" : value.trim();
        int index = text.indexOf(" - ");
        return index > 0 && index + 3 < text.length() ? text.substring(index + 3).trim() : text;
    }

    private static char usdbPrefix(String type) {
        if ("golden".equalsIgnoreCase(type)) return '*';
        if ("freestyle".equalsIgnoreCase(type)) return 'F';
        if ("rap".equalsIgnoreCase(type)) return 'R';
        return ':';
    }

    private static List<File> candidates(File audio, String title) {
        List<File> files = new ArrayList<>();
        File dir = audio.getParentFile();
        if (dir == null || !dir.isDirectory()) return files;
        Set<String> bases = new LinkedHashSet<>();
        addBase(bases, stripExtension(audio.getName()));
        addBase(bases, stripExtension(title));
        for (String base : bases) {
            files.add(new File(dir, base + ".ultrastar.txt"));
            files.add(new File(dir, base + ".karaoke.txt"));
            files.add(new File(dir, base + ".usdx.txt"));
            files.add(new File(dir, base + ".txt"));
        }
        return files;
    }

    private static void addBase(Set<String> bases, String value) {
        String base = value == null ? "" : value.trim();
        if (!base.isEmpty()) bases.add(base);
    }

    private static File resolveLocalFile(String url) {
        if (TextUtils.isEmpty(url)) return null;
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if (TextUtils.isEmpty(scheme)) return new File(url);
            if ("file".equalsIgnoreCase(scheme)) {
                String path = Uri.decode(uri.getPath());
                return TextUtils.isEmpty(path) ? null : new File(path);
            }
            return null;
        } catch (Exception e) {
            return new File(url);
        }
    }

    private static String getTitle(PlayerManager player) {
        if (player == null) return "";
        MediaMetadata metadata = player.getMetadata();
        if (metadata != null && metadata.title != null) return metadata.title.toString();
        return "";
    }

    private static String getArtist(PlayerManager player) {
        if (player == null) return "";
        MediaMetadata metadata = player.getMetadata();
        if (metadata != null && metadata.artist != null) return metadata.artist.toString();
        return "";
    }

    private static String signatureOf(PlayerManager player) {
        if (player == null) return "";
        return signatureOf(player.getKey(), player.getUrl(), player.getDuration());
    }

    private static String signatureOf(String key, String url, long duration) {
        return Util.md5(safe(key) + "|" + safe(url) + "|" + duration);
    }

    private static File boundFile(String signature) {
        if (TextUtils.isEmpty(signature)) return null;
        File dir = Path.cache("karaoke_tracks");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, signature + ".txt");
    }

    private static boolean isHttpUrl(String value) {
        if (TextUtils.isEmpty(value)) return false;
        String url = value.trim().toLowerCase();
        return url.startsWith("http://") || url.startsWith("https://");
    }

    private static String userAgent() {
        return "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36";
    }

    private static String webCookie(String url) {
        try {
            return CookieManager.getInstance().getCookie(url);
        } catch (Exception e) {
            return "";
        }
    }

    static String encode(String text) throws Exception {
        return URLEncoder.encode(text, "UTF-8");
    }

    static String absoluteUrl(String base, String path) {
        if (TextUtils.isEmpty(path)) return "";
        if (path.startsWith("http://") || path.startsWith("https://")) return path;
        if (path.startsWith("//")) return "https:" + path;
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    static String find(Pattern pattern, String text, int group) {
        if (TextUtils.isEmpty(text)) return "";
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(group) : "";
    }

    static String findLast(Pattern pattern, String text, int group) {
        if (TextUtils.isEmpty(text)) return "";
        Matcher matcher = pattern.matcher(text);
        String value = "";
        while (matcher.find()) value = matcher.group(group);
        return value;
    }

    static String html(String text) {
        if (TextUtils.isEmpty(text)) return "";
        return Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString().replace('\u00A0', ' ').replace('\u3000', ' ').trim();
    }

    static String normalizeSearch(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static String cacheKey(String query) {
        String normalized = normalizeSearch(query);
        return TextUtils.isEmpty(normalized) ? safe(query).trim().toLowerCase(Locale.ROOT) : normalized;
    }

    private static List<SearchResult> getSearchCache(String key) {
        if (TextUtils.isEmpty(key)) return null;
        synchronized (SEARCH_CACHE) {
            SearchCache cache = SEARCH_CACHE.get(key);
            if (cache == null) return null;
            if (System.currentTimeMillis() - cache.timestampMs > SEARCH_CACHE_MS) {
                SEARCH_CACHE.remove(key);
                return null;
            }
            return new ArrayList<>(cache.results);
        }
    }

    private static void putSearchCache(String key, List<SearchResult> results) {
        if (TextUtils.isEmpty(key) || results == null || results.isEmpty()) return;
        synchronized (SEARCH_CACHE) {
            SEARCH_CACHE.put(key, new SearchCache(results));
        }
    }

    private static void addUnique(List<SearchResult> target, List<SearchResult> source) {
        if (source == null || source.isEmpty()) return;
        Set<String> exists = new LinkedHashSet<>();
        for (SearchResult result : target) exists.add(result.getUrl());
        for (SearchResult result : source) {
            if (result == null || TextUtils.isEmpty(result.getUrl()) || exists.contains(result.getUrl())) continue;
            target.add(result);
            exists.add(result.getUrl());
        }
    }

    static String emptyDash(String value) {
        return TextUtils.isEmpty(value) ? "-" : value;
    }

    private static String normalizeText(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim() + "\n";
    }

    private static String stripExtension(String name) {
        String value = name == null ? "" : name.trim();
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        int dot = value.lastIndexOf('.');
        if (dot > 0 && value.length() - dot <= 16) value = value.substring(0, dot);
        return value.trim();
    }

    private static String readText(File file) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) builder.append(line).append('\n');
        }
        return builder.toString();
    }

    private static byte[] readBytes(File file, long maxBytes) throws Exception {
        if (file.length() > maxBytes) throw new IllegalStateException("track too large");
        byte[] bytes = new byte[(int) file.length()];
        try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) break;
                offset += read;
            }
            return bytes;
        }
    }

    private static class SearchCache {

        private final long timestampMs;
        private final List<SearchResult> results;

        private SearchCache(List<SearchResult> results) {
            this.timestampMs = System.currentTimeMillis();
            this.results = new ArrayList<>(results);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static class RemoteUrl {

        private final String url;
        private final String cookie;

        private RemoteUrl(String url, String cookie) {
            this.url = normalize(url == null ? "" : url.trim());
            this.cookie = cookie == null ? "" : cookie.trim();
        }

        private static RemoteUrl of(String value) {
            String text = value == null ? "" : value.trim();
            int index = text.indexOf("@Cookie=");
            if (index < 0) return new RemoteUrl(text, "");
            return new RemoteUrl(text.substring(0, index), text.substring(index + 8));
        }

        private static String normalize(String url) {
            Matcher github = GITHUB_BLOB.matcher(url);
            if (github.find()) return "https://raw.githubusercontent.com/" + github.group(1) + "/" + github.group(2) + "/" + github.group(3) + "/" + github.group(4);
            return url.replace("/-/blob/", "/-/raw/");
        }
    }

    public static class SearchResult {

        private final String source;
        private final String title;
        private final String artist;
        private final String note;
        private final String url;
        private final boolean loginRequired;

        SearchResult(String source, String title, String artist, String note, String url, boolean loginRequired) {
            this.source = source == null ? "" : source;
            this.title = title == null ? "" : title;
            this.artist = artist == null ? "" : artist;
            this.note = note == null ? "" : note;
            this.url = url == null ? "" : url;
            this.loginRequired = loginRequired;
        }

        public String getSource() {
            return source;
        }

        public String getTitle() {
            return title;
        }

        public String getArtist() {
            return artist;
        }

        public String getNote() {
            return note;
        }

        public String getUrl() {
            return url;
        }

        public boolean isLoginRequired() {
            return loginRequired;
        }
    }

    public static class ImportResult {

        private final boolean success;
        private final KaraokeTrack track;
        private final String source;
        private final String error;

        private ImportResult(boolean success, KaraokeTrack track, String source, String error) {
            this.success = success;
            this.track = track;
            this.source = source == null ? "" : source;
            this.error = error == null ? "" : error;
        }

        public static ImportResult success(KaraokeTrack track, String source) {
            return new ImportResult(true, track, source, "");
        }

        public static ImportResult fail(String error) {
            return new ImportResult(false, null, "", error);
        }

        public boolean isSuccess() {
            return success;
        }

        public KaraokeTrack getTrack() {
            return track;
        }

        public String getSource() {
            return source;
        }

        public String getError() {
            return error;
        }
    }
}
