package com.fongmi.android.tv.bean;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

public class AiConfig {

    private static final Gson GSON = new Gson();
    public static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/responses";
    public static final String DEFAULT_MODEL = "gpt-4.1-mini";
    public static final String DEFAULT_RECOMMEND_PROMPT = "你是一位专业的影视剧推荐专家，熟悉全球影视内容，包括电视剧、电影、动漫、纪录片等。你的任务是根据用户提供的观影历史和搜索记录，分析用户的偏好，并输出个性化的影视推荐列表。只返回可解析 JSON，不要解释，建议格式为 {\"items\":[{\"title\":\"片名\",\"year\":2024,\"mediaType\":\"movie 或 tv\",\"reason\":\"一句推荐理由\"}]}。优先推荐不同于历史记录和当前影片的作品，推荐数量由提示词自行决定。";

    @SerializedName("enabled")
    private boolean enabled;
    @SerializedName("endpoint")
    private String endpoint;
    @SerializedName("apiKey")
    private String apiKey;
    @SerializedName("model")
    private String model;
    @SerializedName("recommendPrompt")
    private String recommendPrompt;

    public static AiConfig objectFrom(String json) {
        try {
            AiConfig config = GSON.fromJson(json, AiConfig.class);
            return config == null ? new AiConfig().sanitize() : config.sanitize();
        } catch (Throwable e) {
            return new AiConfig().sanitize();
        }
    }

    public AiConfig sanitize() {
        endpoint = trimOr(endpoint, DEFAULT_ENDPOINT);
        apiKey = trimOr(apiKey, "");
        model = trimOr(model, DEFAULT_MODEL);
        recommendPrompt = trimOr(recommendPrompt, DEFAULT_RECOMMEND_PROMPT);
        return this;
    }

    public boolean isReady() {
        sanitize();
        return enabled && !isEmpty(endpoint) && !isEmpty(apiKey) && !isEmpty(model);
    }

    public String toJson() {
        return GSON.toJson(sanitize());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getRecommendPrompt() {
        return recommendPrompt;
    }

    public void setRecommendPrompt(String recommendPrompt) {
        this.recommendPrompt = recommendPrompt;
    }

    private static String trimOr(String value, String fallback) {
        return isEmpty(value) ? fallback : value.trim();
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }
}
