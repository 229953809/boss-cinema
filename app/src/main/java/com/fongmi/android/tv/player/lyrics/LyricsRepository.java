package com.fongmi.android.tv.player.lyrics;

import android.net.Uri;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.LyricsSetting;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Path;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class LyricsRepository {

    public interface Callback {
        void onResult(LyricsResult result);
    }

    public interface SearchCallback {
        void onResult(List<LyricsResult> results, boolean complete);
    }

    private static final String TAG = "lyrics";
    private final LrcLibClient client = new LrcLibClient();
    private final KuwoClient kuwo = new KuwoClient();
    private final KugouClient kugou = new KugouClient();
    private final MiguClient migu = new MiguClient();
    private final QqMusicClient qqMusic = new QqMusicClient();
    private final NeteaseClient netease = new NeteaseClient();
    private final TtmlClient ttml = new TtmlClient();
    private final LyricsMatcher matcher = new LyricsMatcher();

    public void load(LyricsRequest request, Callback callback) {
        load(request, false, callback);
    }

    public void loadPreferWord(LyricsRequest request, Callback callback) {
        load(request, true, callback);
    }

    public void loadPreferWord(LyricsRequest request, boolean forceRefresh, Callback callback) {
        load(request, true, forceRefresh, callback);
    }

    public void search(LyricsRequest request, SearchCallback callback) {
        Task.execute(() -> {
            int sourceMode = LyricsSetting.getSourceMode();
            List<LyricsResult> cached = readSearchCache(request, sourceMode);
            if (!cached.isEmpty()) postSearch(callback, cached, false);
            try {
                if (sourceMode == LyricsSetting.SOURCE_AUTO) searchAuto(request, sourceMode, cached, callback);
                else {
                    List<LyricsResult> results = searchSync(request);
                    if (results.isEmpty() && !cached.isEmpty()) results = cached;
                    writeSearchCache(request, sourceMode, results);
                    postSearch(callback, results, true);
                }
            } catch (Throwable e) {
                if (SpiderDebug.isEnabled()) SpiderDebug.log(TAG, "search failed title=%s error=%s", request.getTitle(), e.getMessage());
                postSearch(callback, List.of(), true);
            }
        });
    }

    public void remember(LyricsRequest request, LyricsResult result) {
        if (request == null || result == null || !result.isValid()) return;
        writeChoice(request, result);
        writeCache(request, LyricsSetting.getSourceMode(), result);
    }

    public boolean hasChoice(LyricsRequest request) {
        LyricsResult choice = readChoice(request);
        return choice != null && choice.isValid() && choice.isCacheCurrent();
    }

    private void load(LyricsRequest request, boolean preferWord, Callback callback) {
        load(request, preferWord, false, callback);
    }

    private void load(LyricsRequest request, boolean preferWord, boolean forceRefresh, Callback callback) {
        if (preferWord && !forceRefresh && LyricsSetting.getSourceMode() == LyricsSetting.SOURCE_AUTO) {
            loadProgressive(request, callback);
            return;
        }
        Task.execute(() -> {
            LyricsResult result = null;
            try {
                result = loadSync(request, preferWord, forceRefresh);
            } catch (Throwable e) {
                if (SpiderDebug.isEnabled()) SpiderDebug.log(TAG, "load failed title=%s error=%s", request.getTitle(), e.getMessage());
            }
            LyricsResult finalResult = result;
            App.post(() -> callback.onResult(finalResult));
        });
    }

    private void loadProgressive(LyricsRequest request, Callback callback) {
        Task.execute(() -> {
            LyricsResult early = null;
            LyricsResult result = null;
            try {
                early = loadQuickSync(request);
                if (early != null && early.isValid()) {
                    LyricsResult finalEarly = early;
                    App.post(() -> callback.onResult(finalEarly));
                }
            } catch (Throwable e) {
                if (SpiderDebug.isEnabled()) SpiderDebug.log(TAG, "quick load failed title=%s error=%s", request.getTitle(), e.getMessage());
            }
            try {
                result = loadSync(request, true, false);
            } catch (Throwable e) {
                if (SpiderDebug.isEnabled()) SpiderDebug.log(TAG, "progressive load failed title=%s error=%s", request.getTitle(), e.getMessage());
            }
            LyricsResult finalResult = result;
            if (shouldNotifyProgress(early, finalResult)) App.post(() -> callback.onResult(finalResult));
            else if ((early == null || !early.isValid()) && (finalResult == null || !finalResult.isValid())) App.post(() -> callback.onResult(null));
        });
    }

    private LyricsResult loadQuickSync(LyricsRequest request) {
        int sourceMode = LyricsSetting.getSourceMode();
        LyricsResult choice = readChoice(request);
        if (choice != null && choice.isValid() && choice.isCacheCurrent()) return choice;
        LyricsResult local = readLocal(request);
        if (local != null && local.isValid()) {
            writeCache(request, sourceMode, local);
            return local;
        }
        LyricsResult cached = readCache(request, sourceMode);
        if (cached != null && cached.isValid() && cached.isCacheCurrent()) return cached;
        LyricsResult lrclib = matcher.best(request, client.findCandidates(request));
        if (lrclib != null && lrclib.isValid()) {
            writeCache(request, sourceMode, lrclib);
            if (SpiderDebug.isEnabled()) SpiderDebug.log(TAG, "quick match title=%s artist=%s result=%s score=%d", request.getTitle(), request.getArtist(), lrclib.getSource(), lrclib.getScore());
        }
        return lrclib;
    }

    private boolean shouldNotifyProgress(LyricsResult early, LyricsResult result) {
        if (result == null || !result.isValid()) return false;
        if (early == null || !early.isValid()) return true;
        if (sameResult(early, result)) return false;
        if (result.hasWordTiming() && !early.hasWordTiming()) return true;
        return weightedScore(result) > weightedScore(early) + 10;
    }

    private LyricsResult loadSync(LyricsRequest request, boolean preferWord, boolean forceRefresh) {
        int sourceMode = LyricsSetting.getSourceMode();
        LyricsResult choice = forceRefresh ? null : readChoice(request);
        if (choice != null && choice.isValid() && choice.isCacheCurrent()) return choice;
        LyricsResult cached = forceRefresh ? null : readCache(request, sourceMode);
        if (cached != null && cached.isValid() && cached.isCacheCurrent() && (!preferWord || isTrustedWord(cached))) return cached;
        LyricsResult remote = cached != null && cached.isValid() && cached.isCacheCurrent() ? cached : null;
        LyricsResult local = readLocal(request);
        if (sourceMode != LyricsSetting.SOURCE_AUTO) return loadSource(request, sourceMode, local);
        if (local != null && local.isValid()) {
            if (!preferWord || local.hasWordTiming()) {
                writeCache(request, sourceMode, local);
                return local;
            }
            if (shouldUseRemote(remote, local)) remote = local;
        }
        LyricsResult qq = qqMusic.find(request);
        if (shouldUseRemote(remote, qq)) remote = qq;
        LyricsResult ttmlResult = ttml.find(request);
        if (shouldUseRemote(remote, ttmlResult)) remote = ttmlResult;
        LyricsResult cloud = netease.find(request);
        if (shouldUseRemote(remote, cloud)) remote = cloud;
        LyricsResult kuwoResult = kuwo.find(request);
        if (shouldUseRemote(remote, kuwoResult)) remote = kuwoResult;
        LyricsResult kugouResult = kugou.find(request);
        if (shouldUseRemote(remote, kugouResult)) remote = kugouResult;
        LyricsResult miguResult = migu.find(request);
        if (shouldUseRemote(remote, miguResult)) remote = miguResult;
        List<LrcLibClient.Entry> candidates = remote == null || !remote.isValid() ? client.findCandidates(request) : List.of();
        if (remote == null || !remote.isValid()) remote = matcher.best(request, candidates);
        if (remote != null && remote.isValid()) writeCache(request, sourceMode, remote);
        if (SpiderDebug.isEnabled()) SpiderDebug.log(TAG, "match title=%s artist=%s candidates=%d result=%s score=%d", request.getTitle(), request.getArtist(), candidates.size(), remote == null ? "none" : remote.getSource(), remote == null ? 0 : remote.getScore());
        return remote;
    }

    private List<LyricsResult> searchSync(LyricsRequest request) {
        int sourceMode = LyricsSetting.getSourceMode();
        ArrayList<LyricsResult> results = new ArrayList<>();
        ArrayList<SearchFuture> futures = new ArrayList<>();
        LyricsResult local = readLocal(request);
        switch (sourceMode) {
            case LyricsSetting.SOURCE_LOCAL -> add(results, local);
            case LyricsSetting.SOURCE_TTML -> add(results, ttml.find(request));
            case LyricsSetting.SOURCE_QQ -> addAll(results, qqMusic.findAll(request, 8));
            case LyricsSetting.SOURCE_NETEASE -> addAll(results, netease.findAll(request, 8));
            case LyricsSetting.SOURCE_KUWO -> addAll(results, kuwo.findAll(request, 8));
            case LyricsSetting.SOURCE_LRCLIB -> addAll(results, matcher.all(request, client.findCandidates(request), 8));
            case LyricsSetting.SOURCE_KUGOU -> addAll(results, kugou.findAll(request, 8));
            case LyricsSetting.SOURCE_MIGU -> addAll(results, migu.findAll(request, 8));
            default -> {
                add(results, local);
                addSearch(futures, "QQMusic", () -> qqMusic.findAll(request, 4));
                addSearch(futures, "AMLL TTML", () -> one(ttml.find(request)));
                addSearch(futures, "Netease", () -> netease.findAll(request, 4));
                addSearch(futures, "Kuwo", () -> kuwo.findAll(request, 4));
                addSearch(futures, "Kugou", () -> kugou.findAll(request, 4));
                addSearch(futures, "Migu", () -> migu.findAll(request, 4));
                addSearch(futures, "LRCLIB", () -> matcher.all(request, client.findCandidates(request), 6));
                collectSearch(results, futures);
            }
        }
        return sorted(results, 24);
    }

    private void searchAuto(LyricsRequest request, int sourceMode, List<LyricsResult> cached, SearchCallback callback) {
        ArrayList<LyricsResult> results = new ArrayList<>(cached == null ? List.of() : cached);
        ArrayList<SearchFuture> futures = new ArrayList<>();
        add(results, readLocal(request));
        if (!results.isEmpty() && (cached == null || cached.isEmpty())) postSearch(callback, sorted(results, 24), false);
        addSearch(futures, "QQMusic", () -> qqMusic.findAll(request, 4));
        addSearch(futures, "AMLL TTML", () -> one(ttml.find(request)));
        addSearch(futures, "Netease", () -> netease.findAll(request, 4));
        addSearch(futures, "Kuwo", () -> kuwo.findAll(request, 4));
        addSearch(futures, "Kugou", () -> kugou.findAll(request, 4));
        addSearch(futures, "Migu", () -> migu.findAll(request, 4));
        addSearch(futures, "LRCLIB", () -> matcher.all(request, client.findCandidates(request), 6));
        collectSearchProgressive(request, sourceMode, results, futures, callback);
    }

    private LyricsResult loadSource(LyricsRequest request, int sourceMode, LyricsResult local) {
        LyricsResult result = switch (sourceMode) {
            case LyricsSetting.SOURCE_LOCAL -> local;
            case LyricsSetting.SOURCE_TTML -> ttml.find(request);
            case LyricsSetting.SOURCE_QQ -> qqMusic.find(request);
            case LyricsSetting.SOURCE_NETEASE -> netease.find(request);
            case LyricsSetting.SOURCE_KUWO -> kuwo.find(request);
            case LyricsSetting.SOURCE_LRCLIB -> matcher.best(request, client.findCandidates(request));
            case LyricsSetting.SOURCE_KUGOU -> kugou.find(request);
            case LyricsSetting.SOURCE_MIGU -> migu.find(request);
            default -> null;
        };
        if (result != null && result.isValid()) writeCache(request, sourceMode, result);
        if (SpiderDebug.isEnabled()) SpiderDebug.log(TAG, "match sourceMode=%d title=%s artist=%s result=%s score=%d", sourceMode, request.getTitle(), request.getArtist(), result == null ? "none" : result.getSource(), result == null ? 0 : result.getScore());
        return result;
    }

    private boolean shouldUseRemote(LyricsResult current, LyricsResult remote) {
        if (remote == null || !remote.isValid()) return false;
        if (current == null || !current.isValid()) return true;
        if (remote.hasWordTiming() && !current.hasWordTiming()) return true;
        if (remote.hasWordTiming() && current.hasWordTiming()) {
            int remotePriority = wordPriority(remote);
            int currentPriority = wordPriority(current);
            if (remotePriority > currentPriority && remote.getScore() >= current.getScore() - 15) return true;
            if (remotePriority < currentPriority && remote.getScore() <= current.getScore() + 15) return false;
        }
        return weightedScore(remote) > weightedScore(current) + 10;
    }

    private boolean isTrustedWord(LyricsResult result) {
        return result != null && result.isValid() && result.hasWordTiming() && wordPriority(result) >= 30;
    }

    private int weightedScore(LyricsResult result) {
        if (result == null) return 0;
        return result.getScore() + (result.hasWordTiming() ? 80 + wordPriority(result) : 0);
    }

    private int wordPriority(LyricsResult result) {
        String source = result == null || result.getSource() == null ? "" : result.getSource();
        if (source.contains("Local TTML")) return 60;
        if (source.contains("AMLL TTML")) return 50;
        if (source.contains("QQMusic")) return 45;
        if (source.contains("Netease")) return 40;
        if (source.contains("Kugou KRC")) return 35;
        if (source.contains("Migu MRC")) return 34;
        if (source.contains("Kuwo")) return 30;
        if (source.contains("Kugou")) return 8;
        if (source.contains("Local")) return 30;
        return 0;
    }

    private void addSearch(List<SearchFuture> futures, String source, SearchAction action) {
        futures.add(new SearchFuture(source, Task.largeExecutor().submit(() -> {
            try {
                return action.run();
            } catch (Throwable e) {
                if (SpiderDebug.isEnabled()) SpiderDebug.log(TAG, "search source=%s failed error=%s", source, e.getMessage());
                return List.of();
            }
        })));
    }

    private void collectSearch(List<LyricsResult> results, List<SearchFuture> futures) {
        long deadline = System.currentTimeMillis() + 22000;
        for (SearchFuture item : futures) {
            try {
                long wait = Math.max(1, deadline - System.currentTimeMillis());
                addAll(results, item.future.get(wait, TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                item.future.cancel(true);
                if (SpiderDebug.isEnabled()) SpiderDebug.log(TAG, "search source=%s timeout", item.source);
            } catch (Throwable e) {
                if (SpiderDebug.isEnabled()) SpiderDebug.log(TAG, "search source=%s failed error=%s", item.source, e.getMessage());
            }
        }
    }

    private void collectSearchProgressive(LyricsRequest request, int sourceMode, List<LyricsResult> results, List<SearchFuture> futures, SearchCallback callback) {
        long deadline = System.currentTimeMillis() + 22000;
        int lastCount = results.size();
        while (!futures.isEmpty() && System.currentTimeMillis() < deadline) {
            boolean changed = false;
            for (int i = futures.size() - 1; i >= 0; i--) {
                SearchFuture item = futures.get(i);
                if (!item.future.isDone()) continue;
                futures.remove(i);
                try {
                    addAll(results, item.future.get());
                    changed = true;
                } catch (Throwable e) {
                    if (SpiderDebug.isEnabled()) SpiderDebug.log(TAG, "search source=%s failed error=%s", item.source, e.getMessage());
                }
            }
            List<LyricsResult> sorted = sorted(results, 24);
            if (changed && sorted.size() != lastCount) {
                lastCount = sorted.size();
                postSearch(callback, sorted, false);
            }
            if (!changed) sleepSearch();
        }
        for (SearchFuture item : futures) {
            item.future.cancel(true);
            if (SpiderDebug.isEnabled()) SpiderDebug.log(TAG, "search source=%s timeout", item.source);
        }
        List<LyricsResult> sorted = sorted(results, 24);
        writeSearchCache(request, sourceMode, sorted);
        postSearch(callback, sorted, true);
    }

    private void sleepSearch() {
        try {
            Thread.sleep(120);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void postSearch(SearchCallback callback, List<LyricsResult> results, boolean complete) {
        List<LyricsResult> finalResults = results == null ? List.of() : new ArrayList<>(results);
        App.post(() -> callback.onResult(finalResults, complete));
    }

    private List<LyricsResult> one(LyricsResult result) {
        ArrayList<LyricsResult> results = new ArrayList<>();
        add(results, result);
        return results;
    }

    private void add(List<LyricsResult> results, LyricsResult result) {
        if (result != null && result.isValid()) results.add(result);
    }

    private void addAll(List<LyricsResult> results, List<LyricsResult> items) {
        if (items == null) return;
        for (LyricsResult item : items) add(results, item);
    }

    private List<LyricsResult> sorted(List<LyricsResult> items, int limit) {
        ArrayList<LyricsResult> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ArrayList<LyricsResult> candidates = new ArrayList<>(items == null ? List.of() : items);
        candidates.sort(Comparator.comparingInt((LyricsResult result) -> weightedScore(result)).reversed());
        for (LyricsResult item : candidates) {
            if (!seen.add(resultKey(item))) continue;
            results.add(item);
            if (results.size() >= limit) break;
        }
        return results;
    }

    private String resultKey(LyricsResult result) {
        long duration = Math.round(result.getDurationMs() / 1000.0);
        return String.join("|",
                safe(result.getSource()),
                LyricsMatcher.normalize(result.getTrackName()),
                LyricsMatcher.normalize(result.getArtistName()),
                String.valueOf(duration));
    }

    private boolean sameResult(LyricsResult first, LyricsResult second) {
        if (first == null || second == null) return first == second;
        return TextUtils.equals(resultKey(first), resultKey(second)) && TextUtils.equals(first.getLyrics(), second.getLyrics());
    }

    private String safe(String text) {
        return text == null ? "" : text.trim();
    }

    private LyricsResult readCache(LyricsRequest request, int sourceMode) {
        File file = cacheFile(request, sourceMode);
        if (!Path.exists(file)) return null;
        try {
            return App.gson().fromJson(Path.read(file), LyricsResult.class);
        } catch (Exception e) {
            return null;
        }
    }

    private List<LyricsResult> readSearchCache(LyricsRequest request, int sourceMode) {
        File file = searchCacheFile(request, sourceMode);
        if (!Path.exists(file)) return List.of();
        ArrayList<LyricsResult> results = new ArrayList<>();
        try {
            LyricsResult[] items = App.gson().fromJson(Path.read(file), LyricsResult[].class);
            if (items != null) for (LyricsResult item : items) if (item != null && item.isValid() && item.isCacheCurrent()) results.add(item);
        } catch (Exception e) {
            return List.of();
        }
        return sorted(results, 24);
    }

    private void writeSearchCache(LyricsRequest request, int sourceMode, List<LyricsResult> results) {
        try {
            Path.write(searchCacheFile(request, sourceMode), App.gson().toJson(results == null ? List.of() : results).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private void writeCache(LyricsRequest request, int sourceMode, LyricsResult result) {
        try {
            Path.write(cacheFile(request, sourceMode), App.gson().toJson(result).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private LyricsResult readChoice(LyricsRequest request) {
        File file = choiceFile(request);
        if (!Path.exists(file)) return null;
        try {
            return App.gson().fromJson(Path.read(file), LyricsResult.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void writeChoice(LyricsRequest request, LyricsResult result) {
        try {
            Path.write(choiceFile(request), App.gson().toJson(result).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private LyricsResult readLocal(LyricsRequest request) {
        File source = sourceFile(request.getUrl());
        if (source == null) return null;
        String name = source.getName();
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return null;
        File parent = source.getParentFile();
        if (parent == null) return null;
        String base = name.substring(0, dot);
        File ttml = findLocal(parent, base, ".ttml", ".TTML");
        if (Path.exists(ttml)) {
            String text = TtmlClient.toEnhancedLrc(Path.read(ttml));
            if (!TextUtils.isEmpty(text) && LyricsParser.hasTimedLine(text)) return new LyricsResult("Local TTML", request.getTitle(), request.getArtist(), request.getAlbum(), text, request.getDurationMs(), true, 104);
        }
        File lrc = findLocal(parent, base, ".lrc", ".LRC");
        if (!Path.exists(lrc)) return null;
        String text = Path.read(lrc);
        if (TextUtils.isEmpty(text)) return null;
        boolean synced = LyricsParser.hasTimedLine(text);
        return new LyricsResult("Local", request.getTitle(), request.getArtist(), request.getAlbum(), text, request.getDurationMs(), synced, 100);
    }

    private File findLocal(File parent, String base, String lower, String upper) {
        File file = new File(parent, base + lower);
        return Path.exists(file) ? file : new File(parent, base + upper);
    }

    private File sourceFile(String url) {
        try {
            if (TextUtils.isEmpty(url)) return null;
            if (url.startsWith("file://")) return new File(Uri.parse(url).getPath());
            if (url.startsWith("/")) return new File(url);
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private File cacheFile(LyricsRequest request, int sourceMode) {
        File dir = Path.cache("lyrics");
        if (!dir.exists()) dir.mkdirs();
        String suffix = LyricsSetting.cacheSuffix(sourceMode);
        return new File(dir, request.signature() + (suffix.isEmpty() ? "" : "-" + suffix) + ".json");
    }

    private File searchCacheFile(LyricsRequest request, int sourceMode) {
        File dir = Path.cache("lyrics");
        if (!dir.exists()) dir.mkdirs();
        String suffix = LyricsSetting.cacheSuffix(sourceMode);
        return new File(dir, request.signature() + (suffix.isEmpty() ? "" : "-" + suffix) + "-search.json");
    }

    private File choiceFile(LyricsRequest request) {
        File dir = new File(cacheDir(), "choices");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, request.signature() + ".json");
    }

    public static int cacheCount() {
        File[] files = cacheDir().listFiles((dir, name) -> name.endsWith(".json"));
        return files == null ? 0 : files.length;
    }

    public static int clearCache() {
        File dir = cacheDir();
        int count = cacheCount();
        Path.clear(dir);
        if (!dir.exists()) dir.mkdirs();
        return count;
    }

    private static File cacheDir() {
        return Path.cache("lyrics");
    }

    private interface SearchAction {
        List<LyricsResult> run();
    }

    private static class SearchFuture {
        private final String source;
        private final Future<List<LyricsResult>> future;

        private SearchFuture(String source, Future<List<LyricsResult>> future) {
            this.source = source;
            this.future = future;
        }
    }
}
