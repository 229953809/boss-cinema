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
import org.json.JSONArray;
import org.json.JSONObject;

public class KaraokeTrackRepository {

    private static final long MAX_SIDECAR_BYTES = 512L * 1024L;
    private static final long MAX_MIDI_BYTES = 2L * 1024L * 1024L;
    private static final long MAX_REMOTE_BYTES = 512L * 1024L;
    private static final Pattern USDB_ID = Pattern.compile("(?i)(?:usdb\\.animux\\.de[\\s\\S]*?[?&]id=|\\busdb\\s*[:#]?\\s*|^)(\\d{3,6})(?:\\D|$)");
    private static final Pattern USDB_FIELD = Pattern.compile("(?is)<tr\\s+class=\"list_tr[12]\"\\s*>\\s*<td>\\s*%s\\s*</td>\\s*<td>(.*?)</td>");
    private static final Pattern USDB_NOTE = Pattern.compile("giveinfo0\\('([^']*)','(-?\\d+)','(-?\\d+)','(-?\\d+)','([^']*)','([^']*)'\\)");
    private static final Pattern UES_ITEM = Pattern.compile("(?is)<li\\s+title=\"See all complete information of ([^\"]+)\"([\\s\\S]*?)(?=\\n\\s*<li\\s+title=\"See all complete information of |\\n\\s*</ul>)");
    private static final Pattern UES_TXT = Pattern.compile("(?is)href=\"([^\"]*/canciones/descargar/txt/[^\"]+)\"");
    private static final Pattern UES_ARTIST = Pattern.compile("(?is)<a\\s+href=\"/[^\"]*/canciones\\?artista=[^\"]*\"[^>]*>(.*?)</a>");
    private static final Pattern UES_TITLE = Pattern.compile("(?is)<a>(.*?)</a>");
    private static final Pattern RSS_ITEM = Pattern.compile("(?is)<item>(.*?)</item>");
    private static final Pattern RSS_TITLE = Pattern.compile("(?is)<title>(.*?)</title>");
    private static final Pattern RSS_GUID = Pattern.compile("(?is)<guid>(\\d+)</guid>");
    private static final Pattern GITHUB_BLOB = Pattern.compile("(?i)^https://github\\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+)$");
    private static final GithubScoreSource[] GITHUB_SCORE_SOURCES = new GithubScoreSource[]{
            new GithubScoreSource("GitHub USDX", "razzertronic", "usdx-songs", "master", "Unlicense"),
            new GithubScoreSource("GitHub UltraStar", "Vasil-Pahomov", "UltraStarSongs", "master", "GPL-3.0")
    };
    private static final Map<String, List<GithubTreeEntry>> GITHUB_TREE_CACHE = new HashMap<>();
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
        Task.execute(() -> {
            List<SearchResult> results = new ArrayList<>();
            try {
                addUnique(results, searchGithubScoreSources(query));
            } catch (Exception ignored) {
            }
            try {
                addUnique(results, searchUltraStarEs(query));
            } catch (Exception ignored) {
            }
            try {
                addUnique(results, searchUsdb(query));
            } catch (Exception ignored) {
            }
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

    private static String getRemoteText(String url, String cookie) throws Exception {
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

    private static List<SearchResult> searchUsdb(String keyword) throws Exception {
        List<SearchResult> results = new ArrayList<>();
        String id = parseUsdbId(keyword);
        if (!TextUtils.isEmpty(id)) {
            results.add(usdbResult(id));
            return results;
        }
        if (TextUtils.isEmpty(keyword) || keyword.trim().length() < 2) return results;
        results.addAll(searchUsdbRss(keyword, "https://usdb.animux.de/rss/rss_new_top10.php"));
        results.addAll(searchUsdbRss(keyword, "https://usdb.animux.de/rss/rss_downloads_top10.php"));
        return results;
    }

    private static SearchResult usdbResult(String id) throws Exception {
        String detailUrl = "https://usdb.animux.de/?link=detail&id=" + id;
        String html = getRemoteText(detailUrl, null);
        String title = parseTitle(html);
        String artist = parseArtist(title);
        String song = parseSong(title);
        String bpm = parseDetailField(html, "BPM");
        String gap = parseDetailField(html, "GAP");
        String note = "BPM " + emptyDash(bpm) + " · GAP " + emptyDash(gap);
        return new SearchResult("USDB", song, artist, note, detailUrl, false);
    }

    private static List<SearchResult> searchUsdbRss(String keyword, String url) throws Exception {
        List<SearchResult> results = new ArrayList<>();
        String html = getRemoteText(url, null);
        Matcher item = RSS_ITEM.matcher(html);
        String normalized = normalizeSearch(keyword);
        while (item.find() && results.size() < 5) {
            String block = item.group(1);
            String title = find(RSS_TITLE, block, 1);
            String id = find(RSS_GUID, block, 1);
            if (TextUtils.isEmpty(title) || TextUtils.isEmpty(id)) continue;
            String clean = html(title);
            if (!normalizeSearch(clean).contains(normalized)) continue;
            results.add(new SearchResult("USDB RSS", parseSong(clean), parseArtist(clean), "USDB #" + id, "https://usdb.animux.de/?link=detail&id=" + id, false));
        }
        return results;
    }

    private static List<SearchResult> searchUltraStarEs(String keyword) throws Exception {
        List<SearchResult> results = new ArrayList<>();
        if (TextUtils.isEmpty(keyword) || keyword.trim().length() < 2) return results;
        String url = "https://ultrastar-es.org/en/canciones?busqueda=" + encode(keyword.trim());
        String html = getRemoteText(url, null);
        Matcher matcher = UES_ITEM.matcher(html);
        while (matcher.find() && results.size() < 12) {
            String fallback = html(matcher.group(1));
            String block = matcher.group(2);
            String txt = absoluteUrl("https://ultrastar-es.org", find(UES_TXT, block, 1));
            if (TextUtils.isEmpty(txt)) continue;
            String artist = html(find(UES_ARTIST, block, 1));
            String title = html(findLast(UES_TITLE, block, 1));
            if (TextUtils.isEmpty(artist)) artist = parseArtist(fallback);
            if (TextUtils.isEmpty(title)) title = parseSong(fallback);
            results.add(new SearchResult("UltraStar-ES", title, artist, "可能需要登录下载", txt, true));
        }
        return results;
    }

    private static List<SearchResult> searchGithubScoreSources(String keyword) throws Exception {
        List<SearchResult> results = new ArrayList<>();
        if (TextUtils.isEmpty(keyword) || keyword.trim().length() < 2) return results;
        for (GithubScoreSource source : GITHUB_SCORE_SOURCES) {
            for (GithubTreeEntry entry : getGithubTree(source)) {
                if (results.size() >= 24) return results;
                if (!matchesKeyword(entry.path, keyword)) continue;
                String label = labelFromPath(entry.path);
                String note = "UltraStar .txt · " + source.license;
                results.add(new SearchResult(source.name, parseSong(label), parseArtist(label), note, githubRawUrl(source, entry.path), false));
            }
        }
        return results;
    }

    private static List<GithubTreeEntry> getGithubTree(GithubScoreSource source) throws Exception {
        synchronized (GITHUB_TREE_CACHE) {
            List<GithubTreeEntry> cached = GITHUB_TREE_CACHE.get(source.key());
            if (cached != null) return cached;
        }
        String url = "https://api.github.com/repos/" + source.owner + "/" + source.repo + "/git/trees/" + source.branch + "?recursive=1";
        String json = getRemoteText(url, null);
        JSONArray tree = new JSONObject(json).optJSONArray("tree");
        List<GithubTreeEntry> entries = new ArrayList<>();
        if (tree != null) {
            for (int i = 0; i < tree.length(); i++) {
                JSONObject item = tree.optJSONObject(i);
                if (item == null || !"blob".equals(item.optString("type"))) continue;
                String path = item.optString("path");
                int size = item.optInt("size", 0);
                if (!path.toLowerCase(Locale.ROOT).endsWith(".txt")) continue;
                if (size <= 0 || size > MAX_REMOTE_BYTES) continue;
                entries.add(new GithubTreeEntry(path, size));
            }
        }
        synchronized (GITHUB_TREE_CACHE) {
            GITHUB_TREE_CACHE.put(source.key(), entries);
        }
        return entries;
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

    private static String parseUsdbId(String text) {
        if (TextUtils.isEmpty(text)) return "";
        Matcher matcher = USDB_ID.matcher(text.trim());
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String parseDetailField(String html, String field) {
        Matcher matcher = Pattern.compile(String.format(Locale.US, USDB_FIELD.pattern(), Pattern.quote(field))).matcher(html);
        return matcher.find() ? html(matcher.group(1)).trim() : "";
    }

    private static String parseTitle(String html) {
        String title = find(Pattern.compile("(?is)<title>\\s*USDB\\s*-\\s*(.*?)\\s*</title>"), html, 1);
        if (!TextUtils.isEmpty(title)) return html(title);
        return html(find(Pattern.compile("(?is)<th[^>]*>\\s*<span[^>]*>\\s*<b>(.*?)</b>"), html, 1));
    }

    private static String parseArtist(String value) {
        String text = value == null ? "" : value.trim();
        int index = text.indexOf(" - ");
        return index > 0 ? text.substring(0, index).trim() : "";
    }

    private static String parseSong(String value) {
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

    private static String encode(String text) throws Exception {
        return URLEncoder.encode(text, "UTF-8");
    }

    private static String encodePath(String path) throws Exception {
        StringBuilder builder = new StringBuilder();
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) builder.append('/');
            builder.append(URLEncoder.encode(parts[i], "UTF-8").replace("+", "%20"));
        }
        return builder.toString();
    }

    private static String absoluteUrl(String base, String path) {
        if (TextUtils.isEmpty(path)) return "";
        if (path.startsWith("http://") || path.startsWith("https://")) return path;
        if (path.startsWith("//")) return "https:" + path;
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    private static String find(Pattern pattern, String text, int group) {
        if (TextUtils.isEmpty(text)) return "";
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(group) : "";
    }

    private static String findLast(Pattern pattern, String text, int group) {
        if (TextUtils.isEmpty(text)) return "";
        Matcher matcher = pattern.matcher(text);
        String value = "";
        while (matcher.find()) value = matcher.group(group);
        return value;
    }

    private static String html(String text) {
        if (TextUtils.isEmpty(text)) return "";
        return Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString().replace('\u00A0', ' ').replace('\u3000', ' ').trim();
    }

    private static String normalizeSearch(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static boolean matchesKeyword(String path, String keyword) {
        String normalizedPath = normalizeSearch(path);
        String normalizedKeyword = normalizeSearch(keyword);
        if (TextUtils.isEmpty(normalizedPath) || TextUtils.isEmpty(normalizedKeyword)) return false;
        if (normalizedPath.contains(normalizedKeyword)) return true;
        for (String token : keyword.trim().split("[^\\p{L}\\p{N}]+")) {
            String normalizedToken = normalizeSearch(token);
            if (normalizedToken.length() >= 2 && !normalizedPath.contains(normalizedToken)) return false;
        }
        return true;
    }

    private static String labelFromPath(String path) {
        String value = stripExtension(path);
        int slash = value.lastIndexOf('/');
        String file = slash >= 0 ? value.substring(slash + 1) : value;
        if (isGenericSongFile(file) && slash > 0) {
            int parentSlash = value.lastIndexOf('/', slash - 1);
            return value.substring(parentSlash + 1, slash);
        }
        return file.replaceAll("(?i)\\s*\\((?:minus|plus|duet|karaoke)\\)\\s*$", "").trim();
    }

    private static boolean isGenericSongFile(String value) {
        String normalized = normalizeSearch(value);
        return "s".equals(normalized) || "sm".equals(normalized) || "sp".equals(normalized) || "song".equals(normalized);
    }

    private static String githubRawUrl(GithubScoreSource source, String path) throws Exception {
        return "https://raw.githubusercontent.com/" + source.owner + "/" + source.repo + "/" + source.branch + "/" + encodePath(path);
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

    private static String emptyDash(String value) {
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

    private static class GithubScoreSource {

        private final String name;
        private final String owner;
        private final String repo;
        private final String branch;
        private final String license;

        private GithubScoreSource(String name, String owner, String repo, String branch, String license) {
            this.name = name;
            this.owner = owner;
            this.repo = repo;
            this.branch = branch;
            this.license = license;
        }

        private String key() {
            return owner + "/" + repo + "/" + branch;
        }
    }

    private static class GithubTreeEntry {

        private final String path;
        private final int size;

        private GithubTreeEntry(String path, int size) {
            this.path = path;
            this.size = size;
        }
    }

    public static class SearchResult {

        private final String source;
        private final String title;
        private final String artist;
        private final String note;
        private final String url;
        private final boolean loginRequired;

        private SearchResult(String source, String title, String artist, String note, String url, boolean loginRequired) {
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
