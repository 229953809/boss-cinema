package com.fongmi.android.tv.service;

import com.fongmi.android.tv.bean.AiConfig;
import com.fongmi.android.tv.bean.TmdbItem;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AiRecommendationServiceTest {

    @Test
    public void extractOutputText_readsResponsesConvenienceField() {
        String body = "{\"output_text\":\"{\\\"items\\\":[{\\\"title\\\":\\\"想见你\\\"}]}\"}";

        assertEquals("{\"items\":[{\"title\":\"想见你\"}]}", AiRecommendationService.extractOutputText(body));
    }

    @Test
    public void extractOutputText_readsNestedResponsesContentText() {
        String body = "{\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"{\\\"items\\\":[{\\\"title\\\":\\\"黑暗荣耀\\\"}]}\"}]}]}";

        assertEquals("{\"items\":[{\"title\":\"黑暗荣耀\"}]}", AiRecommendationService.extractOutputText(body));
    }

    @Test
    public void parseRecommendations_acceptsObjectArrayAndFencedJson() {
        String text = "```json\n{\"items\":[{\"title\":\"漫长的季节\",\"year\":2023,\"mediaType\":\"tv\",\"reason\":\"悬疑气质相近\"}]}\n```";

        List<AiRecommendationService.AiRecommendation> items = AiRecommendationService.parseRecommendations(text);

        assertEquals(1, items.size());
        assertEquals("漫长的季节", items.get(0).title);
        assertEquals(2023, items.get(0).year);
        assertEquals("tv", items.get(0).mediaType);
    }

    @Test
    public void fingerprint_changesWhenSearchRecordsOrPromptChanges() {
        AiConfig first = AiConfig.objectFrom("{\"enabled\":true,\"endpoint\":\"https://api.openai.com/v1/responses\",\"apiKey\":\"sk-test\",\"model\":\"gpt-4.1-mini\",\"recommendPrompt\":\"p1\"}");
        AiConfig second = AiConfig.objectFrom("{\"enabled\":true,\"endpoint\":\"https://api.openai.com/v1/responses\",\"apiKey\":\"sk-test\",\"model\":\"gpt-4.1-mini\",\"recommendPrompt\":\"p2\"}");

        String base = AiRecommendationService.fingerprint("A", "h1", "[\"x\"]", first);
        String changedSearch = AiRecommendationService.fingerprint("A", "h1", "[\"x\",\"y\"]", first);
        String changedPrompt = AiRecommendationService.fingerprint("A", "h1", "[\"x\"]", second);

        assertFalse(base.equals(changedSearch));
        assertFalse(base.equals(changedPrompt));
    }

    @Test
    public void pageAllCandidates_keepsAllAiItemsInsteadOfDefaultPageSize() {
        List<PersonalRecommendationService.RecommendationCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < PersonalRecommendationService.DEFAULT_PAGE_SIZE + 5; i++) {
            TmdbItem item = new TmdbItem(i + 1, "movie", "推荐" + i, "", "", "", "");
            candidates.add(new PersonalRecommendationService.RecommendationCandidate(item, "ai:" + i, "推荐" + i, 100 - i, i));
        }

        PersonalRecommendationService.RecommendationPage page = AiRecommendationService.pageAllCandidates(candidates, "fp");

        assertEquals(PersonalRecommendationService.DEFAULT_PAGE_SIZE + 5, page.getItems().size());
        assertFalse(page.hasMore());
    }

    @Test
    public void bestDoubanItem_prefersMatchingTypeAndYearForAiFallback() {
        List<PersonalRecommendationService.DoubanSubject> subjects = new ArrayList<>();
        subjects.add(PersonalRecommendationService.DoubanSubject.from(com.google.gson.JsonParser.parseString("{\"id\":\"1\",\"title\":\"想见你\",\"type\":\"movie\",\"year\":\"2023\",\"img\":\"movie.jpg\"}").getAsJsonObject()));
        subjects.add(PersonalRecommendationService.DoubanSubject.from(com.google.gson.JsonParser.parseString("{\"id\":\"2\",\"title\":\"想见你\",\"type\":\"tv\",\"year\":\"2019\",\"img\":\"tv.jpg\"}").getAsJsonObject()));

        TmdbItem item = PersonalRecommendationService.bestDoubanItem("想见你", "tv", 2019, subjects);

        assertEquals(-2, item.getTmdbId());
        assertEquals("tv", item.getMediaType());
        assertTrue(item.getPosterUrl().contains("tv.jpg"));
    }
}
