package com.fongmi.android.tv.service;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.bean.AiConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.TmdbConfig;
import com.fongmi.android.tv.bean.TmdbItem;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.helper.TmdbMatcher;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Path;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class AiRecommendationService {

    private static final int MAX_CONTEXT_ITEMS = 12;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int CONNECT_TIMEOUT_SECONDS = 8;
    private static final int READ_TIMEOUT_SECONDS = 15;
    private static final int CALL_TIMEOUT_SECONDS = 18;

    private final TmdbService tmdbService;
    private final TmdbConfig tmdbConfig;
    private final TmdbMatcher tmdbMatcher;
    private final AiConfig config;

    public AiRecommendationService(TmdbService tmdbService, TmdbConfig tmdbConfig) {
        this.tmdbService = tmdbService == null ? new TmdbService() : tmdbService;
        this.tmdbConfig = tmdbConfig == null ? new TmdbConfig() : tmdbConfig;
        this.tmdbMatcher = new TmdbMatcher(this.tmdbService, this.tmdbConfig);
        this.config = AiConfig.objectFrom(Setting.getAiConfig());
    }

    private AiRecommendationService(TmdbService tmdbService, TmdbConfig tmdbConfig, AiConfig config) {
        this.tmdbService = tmdbService == null ? new TmdbService() : tmdbService;
        this.tmdbConfig = tmdbConfig == null ? new TmdbConfig() : tmdbConfig;
        this.tmdbMatcher = new TmdbMatcher(this.tmdbService, this.tmdbConfig);
        this.config = config == null ? new AiConfig().sanitize() : config.sanitize();
    }

    public PersonalRecommendationService.RecommendationPage load(@Nullable Vod currentVod, @Nullable String currentTitle, @Nullable String historyFingerprint, int pageSize) {
        String fingerprint = fingerprint(currentTitle, historyFingerprint, Setting.getKeyword(), config);
        if (!config.isReady()) return PersonalRecommendationService.RecommendationPage.empty(fingerprint);
        List<AiRecommendation> recommendations = readCache(fingerprint);
        if (recommendations.isEmpty()) {
            recommendations = requestRecommendations(currentVod, currentTitle);
            if (!recommendations.isEmpty()) writeCache(fingerprint, recommendations);
        }
        List<PersonalRecommendationService.RecommendationCandidate> candidates = new ArrayList<>();
        int order = 0;
        for (AiRecommendation recommendation : recommendations) {
            TmdbItem item = resolveItem(recommendation);
            if (item == null || isBlank(item.getTitle())) continue;
            String normalized = PersonalRecommendationService.normalizeTitle(item.getTitle());
            if (isBlank(normalized)) continue;
            candidates.add(new PersonalRecommendationService.RecommendationCandidate(item, aiKey(item, normalized), normalized, 100.0 - order, order));
            order++;
        }
        return pageAllCandidates(candidates, fingerprint);
    }

    public static TestResult testConfig(AiConfig config) {
        AiConfig safe = config == null ? new AiConfig().sanitize() : config.sanitize();
        if (!safe.isReady()) return TestResult.failed("请先启用智能推荐，并填写端点、API key 和模型。");
        AiRecommendationService service = new AiRecommendationService(null, null, safe);
        JsonObject body = new JsonObject();
        body.addProperty("model", safe.getModel());
        body.addProperty("input", "这是 AI 推荐配置连通性测试。请只返回 JSON: {\"items\":[{\"title\":\"流浪地球\",\"year\":2019,\"mediaType\":\"movie\",\"reason\":\"测试推荐\"}]}");
        try {
            Request request = new Request.Builder()
                    .url(safe.getEndpoint())
                    .header("Authorization", "Bearer " + safe.getApiKey())
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
            try (Response response = service.client().newCall(request).execute()) {
                String responseBody = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) return TestResult.failed("HTTP " + response.code() + ": " + excerpt(responseBody));
                List<AiRecommendation> recommendations = parseRecommendations(extractOutputText(responseBody));
                if (recommendations.isEmpty()) return TestResult.failed("接口已响应，但没有解析到推荐 JSON。");
                return TestResult.success(recommendations.size(), recommendations.get(0).title);
            }
        } catch (Throwable e) {
            return TestResult.failed(e.getMessage());
        }
    }

    static String fingerprint(String currentTitle, String historyFingerprint, String searchRecords, AiConfig config) {
        AiConfig safe = config == null ? new AiConfig().sanitize() : config.sanitize();
        String value = "v1|"
                + Objects.toString(safe.getEndpoint(), "") + "|"
                + Objects.toString(safe.getModel(), "") + "|"
                + Objects.toString(safe.getRecommendPrompt(), "") + "|"
                + PersonalRecommendationService.normalizeTitle(currentTitle) + "|"
                + Objects.toString(historyFingerprint, "") + "|"
                + normalizeSearchRecords(searchRecords);
        return md5(value);
    }

    static String extractOutputText(String body) {
        if (isBlank(body)) return "";
        try {
            JsonElement element = JsonParser.parseString(body);
            if (element == null || !element.isJsonObject()) return "";
            JsonObject root = element.getAsJsonObject();
            String direct = string(root, "output_text");
            if (!isBlank(direct)) return direct;
            JsonArray output = array(root, "output");
            StringBuilder builder = new StringBuilder();
            for (JsonElement item : output) {
                if (!item.isJsonObject()) continue;
                JsonObject outputObject = item.getAsJsonObject();
                appendText(builder, outputObject);
                for (JsonElement content : array(outputObject, "content")) {
                    if (content.isJsonObject()) appendText(builder, content.getAsJsonObject());
                }
            }
            return builder.toString().trim();
        } catch (Throwable e) {
            return "";
        }
    }

    static List<AiRecommendation> parseRecommendations(String text) {
        List<AiRecommendation> items = new ArrayList<>();
        String json = extractJson(text);
        if (isBlank(json)) return items;
        try {
            JsonElement element = JsonParser.parseString(json);
            JsonArray array;
            if (element.isJsonArray()) {
                array = element.getAsJsonArray();
            } else if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                array = object.has("items") && object.get("items").isJsonArray()
                        ? object.getAsJsonArray("items")
                        : object.has("recommendations") && object.get("recommendations").isJsonArray() ? object.getAsJsonArray("recommendations") : new JsonArray();
            } else {
                array = new JsonArray();
            }
            for (JsonElement item : array) {
                if (!item.isJsonObject()) continue;
                AiRecommendation recommendation = AiRecommendation.from(item.getAsJsonObject());
                if (recommendation != null) items.add(recommendation);
            }
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
        return items;
    }

    private List<AiRecommendation> requestRecommendations(Vod currentVod, String currentTitle) {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.getModel());
        body.addProperty("input", buildPrompt(currentVod, currentTitle));
        try {
            Request request = new Request.Builder()
                    .url(config.getEndpoint())
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
            try (Response response = client().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                SpiderDebug.log("ai-rec", "request failed code=%d", response.code());
                return new ArrayList<>();
            }
            return parseRecommendations(extractOutputText(response.body().string()));
            }
        } catch (Throwable e) {
            SpiderDebug.log("ai-rec", "request failed error=%s", e.getMessage());
            return new ArrayList<>();
        }
    }

    private OkHttpClient client() {
        return com.github.catvod.net.OkHttp.client().newBuilder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    private String buildPrompt(Vod currentVod, String currentTitle) {
        StringBuilder builder = new StringBuilder();
        builder.append(config.getRecommendPrompt()).append("\n\n");
        builder.append("当前影片: ").append(Objects.toString(currentTitle, "")).append('\n');
        builder.append("播放历史:\n");
        for (String title : historyTitles(currentVod)) builder.append("- ").append(title).append('\n');
        builder.append("搜索记录:\n");
        for (String keyword : searchKeywords()) builder.append("- ").append(keyword).append('\n');
        return builder.toString();
    }

    private List<String> historyTitles(Vod currentVod) {
        List<String> titles = new ArrayList<>();
        String current = currentVod == null ? "" : currentVod.getName();
        try {
            for (History history : History.get()) {
                if (history == null || isBlank(history.getVodName())) continue;
                if (PersonalRecommendationService.normalizeTitle(history.getVodName()).equals(PersonalRecommendationService.normalizeTitle(current))) continue;
                addUnique(titles, history.getVodName());
                if (titles.size() >= MAX_CONTEXT_ITEMS) break;
            }
        } catch (Throwable e) {
            SpiderDebug.log("ai-rec", "history read failed: %s", e.getMessage());
        }
        return titles;
    }

    private List<String> searchKeywords() {
        List<String> keywords = new ArrayList<>();
        try {
            JsonElement element = JsonParser.parseString(Setting.getKeyword());
            if (element != null && element.isJsonArray()) {
                for (JsonElement item : element.getAsJsonArray()) {
                    if (!item.isJsonPrimitive()) continue;
                    addUnique(keywords, item.getAsString());
                    if (keywords.size() >= MAX_CONTEXT_ITEMS) break;
                }
            }
        } catch (Throwable ignored) {
        }
        return keywords;
    }

    private TmdbItem resolveItem(AiRecommendation recommendation) {
        if (recommendation == null || isBlank(recommendation.title)) return null;
        String mediaType = "tv".equals(recommendation.mediaType) ? "tv" : "movie";
        if (tmdbConfig.isReady()) {
            try {
                TmdbItem item = tmdbMatcher.searchAndMatch(recommendation.title);
                if (item != null) return withReason(item, recommendation.reason);
            } catch (Throwable e) {
                SpiderDebug.log("ai-rec", "tmdb resolve failed title=%s error=%s", recommendation.title, e.getMessage());
            }
        }
        try {
            TmdbItem item = new PersonalRecommendationService(tmdbService, tmdbConfig).matchDoubanItem(recommendation.title, mediaType, recommendation.year);
            if (item != null) return withReason(item, recommendation.reason);
        } catch (Throwable e) {
            SpiderDebug.log("ai-rec", "douban resolve failed title=%s error=%s", recommendation.title, e.getMessage());
        }
        String subtitle = recommendation.subtitle();
        return new TmdbItem(-Math.abs((recommendation.title + recommendation.year + mediaType).hashCode()), mediaType, recommendation.title, subtitle, recommendation.reason, "", "", "", 0.0);
    }

    static PersonalRecommendationService.RecommendationPage pageAllCandidates(List<PersonalRecommendationService.RecommendationCandidate> candidates, String fingerprint) {
        List<PersonalRecommendationService.RecommendationCandidate> ranked = PersonalRecommendationService.rankCandidates(candidates, Integer.MAX_VALUE);
        return PersonalRecommendationService.pageItems(ranked, 0, Math.max(1, ranked.size()), fingerprint, false);
    }

    private TmdbItem withReason(TmdbItem item, String reason) {
        if (item == null || isBlank(reason)) return item;
        return new TmdbItem(
                item.getTmdbId(),
                item.getMediaType(),
                item.getTitle(),
                item.getSubtitle(),
                reason,
                item.getPosterUrl(),
                item.getBackdropUrl(),
                item.getCredit(),
                item.getRating(),
                item.getOriginalLanguage(),
                item.getOriginCountry(),
                item.getGenreIds(),
                item.getDepartment()
        );
    }

    private List<AiRecommendation> readCache(String fingerprint) {
        try {
            File file = cacheFile(fingerprint);
            if (file == null || !file.exists() || file.length() <= 0) return new ArrayList<>();
            return parseRecommendations(Path.read(file));
        } catch (Throwable e) {
            return new ArrayList<>();
        }
    }

    private void writeCache(String fingerprint, List<AiRecommendation> recommendations) {
        try {
            File file = cacheFile(fingerprint);
            if (file == null || recommendations == null || recommendations.isEmpty()) return;
            JsonArray array = new JsonArray();
            for (AiRecommendation recommendation : recommendations) array.add(recommendation.toJson());
            JsonObject root = new JsonObject();
            root.add("items", array);
            Path.write(file, root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    private File cacheFile(String fingerprint) {
        try {
            File dir = new File(Path.cache(), "ai_rec");
            if (!dir.exists()) dir.mkdirs();
            return new File(dir, fingerprint + ".json");
        } catch (Throwable e) {
            return null;
        }
    }

    private static String normalizeSearchRecords(String searchRecords) {
        if (isBlank(searchRecords)) return "";
        try {
            JsonElement element = JsonParser.parseString(searchRecords);
            if (!element.isJsonArray()) return PersonalRecommendationService.normalizeTitle(searchRecords);
            List<String> values = new ArrayList<>();
            for (JsonElement item : element.getAsJsonArray()) {
                if (!item.isJsonPrimitive()) continue;
                String normalized = PersonalRecommendationService.normalizeTitle(item.getAsString());
                if (!isBlank(normalized) && !values.contains(normalized)) values.add(normalized);
            }
            return String.join("|", values);
        } catch (Throwable e) {
            return PersonalRecommendationService.normalizeTitle(searchRecords);
        }
    }

    private static String extractJson(String text) {
        String value = Objects.toString(text, "").trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```[a-zA-Z]*", "").replaceFirst("```$", "").trim();
        }
        if (value.startsWith("{") || value.startsWith("[")) return value;
        int objectStart = value.indexOf('{');
        int arrayStart = value.indexOf('[');
        if (objectStart < 0 && arrayStart < 0) return "";
        boolean useArray = arrayStart >= 0 && (objectStart < 0 || arrayStart < objectStart);
        int start = useArray ? arrayStart : objectStart;
        int end = useArray ? value.lastIndexOf(']') : value.lastIndexOf('}');
        return end > start ? value.substring(start, end + 1) : "";
    }

    private static void appendText(StringBuilder builder, JsonObject object) {
        String text = string(object, "text");
        if (isBlank(text)) text = string(object, "output_text");
        if (isBlank(text)) return;
        if (builder.length() > 0) builder.append('\n');
        builder.append(text);
    }

    private static JsonArray array(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : new JsonArray();
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull() || !object.get(key).isJsonPrimitive()) return "";
        return Objects.toString(object.get(key).getAsString(), "").trim();
    }

    private static void addUnique(List<String> values, String value) {
        if (isBlank(value)) return;
        String normalized = PersonalRecommendationService.normalizeTitle(value);
        for (String item : values) if (PersonalRecommendationService.normalizeTitle(item).equals(normalized)) return;
        values.add(value.trim());
    }

    private static String aiKey(TmdbItem item, String normalized) {
        return item.getTmdbId() > 0 ? item.getMediaType() + ":" + item.getTmdbId() : "ai:" + normalized;
    }

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    private static String md5(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(Objects.toString(text, "").getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte value : bytes) builder.append(String.format(Locale.US, "%02x", value));
            return builder.toString();
        } catch (Throwable e) {
            return Integer.toHexString(Objects.toString(text, "").hashCode());
        }
    }

    private static String excerpt(String text) {
        String value = Objects.toString(text, "").replace('\n', ' ').trim();
        return value.length() > 160 ? value.substring(0, 160) : value;
    }

    public static final class TestResult {

        private final boolean success;
        private final String message;
        private final int count;
        private final String sampleTitle;

        private TestResult(boolean success, String message, int count, String sampleTitle) {
            this.success = success;
            this.message = message == null ? "" : message;
            this.count = count;
            this.sampleTitle = sampleTitle == null ? "" : sampleTitle;
        }

        static TestResult success(int count, String sampleTitle) {
            return new TestResult(true, "", count, sampleTitle);
        }

        static TestResult failed(String message) {
            return new TestResult(false, message, 0, "");
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public int getCount() {
            return count;
        }

        public String getSampleTitle() {
            return sampleTitle;
        }
    }

    static final class AiRecommendation {

        final String title;
        final int year;
        final String mediaType;
        final String reason;

        private AiRecommendation(String title, int year, String mediaType, String reason) {
            this.title = title == null ? "" : title.trim();
            this.year = year;
            this.mediaType = "tv".equals(mediaType) ? "tv" : "movie";
            this.reason = reason == null ? "" : reason.trim();
        }

        static AiRecommendation from(JsonObject object) {
            String title = firstString(object, "title", "name", "vodName");
            if (isBlank(title)) return null;
            String mediaType = firstString(object, "mediaType", "type", "category");
            int year = firstInt(object, "year", "releaseYear");
            String reason = firstString(object, "reason", "desc", "overview");
            return new AiRecommendation(title, year, mediaType, reason);
        }

        JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("title", title);
            object.addProperty("year", year);
            object.addProperty("mediaType", mediaType);
            object.addProperty("reason", reason);
            return object;
        }

        String subtitle() {
            List<String> parts = new ArrayList<>();
            parts.add("tv".equals(mediaType) ? "剧集" : "电影");
            if (year > 0) parts.add(String.valueOf(year));
            if (!isBlank(reason)) parts.add(reason);
            return String.join(" · ", parts);
        }

        private static String firstString(JsonObject object, String... keys) {
            for (String key : keys) {
                String value = string(object, key);
                if (!isBlank(value)) return value;
            }
            return "";
        }

        private static int firstInt(JsonObject object, String... keys) {
            for (String key : keys) {
                try {
                    String value = string(object, key);
                    if (!isBlank(value)) return Integer.parseInt(value.replaceAll("[^0-9]", ""));
                } catch (Throwable ignored) {
                }
            }
            return 0;
        }
    }
}
