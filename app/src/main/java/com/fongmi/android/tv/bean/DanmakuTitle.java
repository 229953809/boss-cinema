package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 弹幕搜索结果的剧名/集数解析工具。
 * <p>
 * webhtv 的 {@link Danmaku} 没有显式的 animeTitle/episode 字段，剧名与集数都嵌在 name 字符串里
 * （例如："你是迟来的欢喜(2026)【电视剧】 - 【IMGO】 第1集 ..."）。本类负责从 name 中提取：
 * <ul>
 *   <li>集数 {@link #parseEpisode(CharSequence)}（支持"第N集/S0xE0y/EP/E/Episode"）</li>
 *   <li>剧名分组键 {@link #titleKey(Danmaku)}：去掉来源标记、集数标记、年份、类型标记后的可读剧名前缀；
 *       解析失败返回 null（调用方据此把该条作为普通项平铺、不折叠）</li>
 *   <li>折叠头文案 {@link #headerTitle(String, int, boolean)}："[+/-] 剧名 (N集)"</li>
 * </ul>
 * 集数解析正则复用自 dev 原版 DanmakuAdapter。
 */
public final class DanmakuTitle {

    public static final int EPISODE_UNKNOWN = Integer.MAX_VALUE;

    private static final Pattern EPISODE_CN = Pattern.compile("第\\s*0*([0-9]{1,5})\\s*[集话話回期章节節]");
    private static final Pattern EPISODE_SEASON = Pattern.compile("\\bS\\d{1,2}\\s*E\\s*0*([0-9]{1,5})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EPISODE_EP = Pattern.compile("\\b(?:EP|E|Episode)\\s*0*([0-9]{1,5})\\b", Pattern.CASE_INSENSITIVE);

    /** "from xxx" 形式的来源标记 */
    private static final Pattern SOURCE_INLINE = Pattern.compile("\\s*\\bfrom\\s+[^\\s\\-]+\\s*-?\\s*", Pattern.CASE_INSENSITIVE);
    /** 【】或[]包裹的来源/类型标记 */
    private static final Pattern SOURCE_MARK = Pattern.compile("[【\\[]([^】\\]]{1,24})[】\\]]");
    /** URL */
    private static final Pattern URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
    /** (2026) 形式的年份括注 */
    private static final Pattern YEAR = Pattern.compile("[(\\[（【]\\s*(?:19|20)\\d{2}\\s*[)\\]）】]");
    /** " - " 形式的分隔符（剧名与集数/来源之间） */
    private static final Pattern DASH = Pattern.compile("\\s*[-－—~～]\\s*");
    /** 连续空白 */
    private static final Pattern BLANK = Pattern.compile("\\s{2,}");

    private DanmakuTitle() {
    }

    /** 解析集数；无法解析返回 {@link #EPISODE_UNKNOWN}。 */
    public static int parseEpisode(CharSequence text) {
        int episode = matchEpisode(EPISODE_CN, text);
        if (episode == EPISODE_UNKNOWN) episode = matchEpisode(EPISODE_SEASON, text);
        if (episode == EPISODE_UNKNOWN) episode = matchEpisode(EPISODE_EP, text);
        return episode;
    }

    /** 解析弹幕项的集数。 */
    public static int getEpisode(@NonNull Danmaku item) {
        return parseEpisode(item.getName());
    }

    /**
     * 提取剧名分组键：清洗 name 中的来源/集数/年份/类型/URL 标记后取前缀。
     * 解析失败（清洗后为空或无稳定剧名）返回 null，调用方据此把该条平铺、不折叠。
     */
    @Nullable
    public static String titleKey(@NonNull Danmaku item) {
        return cleanTitle(item.getName());
    }

    /**
     * 显示用剧名：优先用 {@link #titleKey} 清洗结果；失败回退到 name 去来源标记后的文本。
     */
    @NonNull
    public static String displayTitle(@NonNull Danmaku item) {
        String title = cleanTitle(item.getName());
        if (!TextUtils.isEmpty(title)) return title;
        // 回退：仅去掉来源内联标记，保留原名
        String fallback = SOURCE_INLINE.matcher(item.getName()).replaceAll(" ");
        fallback = SOURCE_MARK.matcher(fallback).replaceAll(" ");
        fallback = BLANK.matcher(fallback).replaceAll(" ").trim();
        return fallback.isEmpty() ? item.getName() : fallback;
    }

    /** 折叠头文案："[+/-] 剧名 (N集)"。 */
    @NonNull
    public static String headerTitle(@NonNull String title, int count, boolean expanded) {
        return (expanded ? "[-] " : "[+] ") + title + " (" + count + "集)";
    }

    @Nullable
    private static String cleanTitle(String text) {
        if (TextUtils.isEmpty(text)) return null;
        String value = text;
        value = URL.matcher(value).replaceAll(" ");
        value = SOURCE_INLINE.matcher(value).replaceAll(" ");
        // 去掉所有【】/[]标记（来源、类型如"电视剧"等）
        value = SOURCE_MARK.matcher(value).replaceAll(" ");
        value = YEAR.matcher(value).replaceAll(" ");
        // 在第一个 " - " 分隔符处截断（之后通常是集数/来源）
        Matcher dash = DASH.matcher(value);
        if (dash.find()) value = value.substring(0, dash.start());
        value = BLANK.matcher(value).replaceAll(" ").trim();
        return value.isEmpty() ? null : value;
    }

    private static int matchEpisode(Pattern pattern, CharSequence text) {
        if (text == null || text.length() == 0) return EPISODE_UNKNOWN;
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return EPISODE_UNKNOWN;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (Exception ignored) {
            return EPISODE_UNKNOWN;
        }
    }
}
