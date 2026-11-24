package backend.post.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import backend.analysis.model.dto.FoodAnalysisResult;
import backend.analysis.model.dto.FoodAnalysisResult.FoodItem;
import backend.analysis.model.dto.FoodAnalyzerResponse;
import backend.analysis.model.entity.FoodAnalysis;
import backend.analysis.model.entity.FoodTag;
import backend.analysis.model.repository.FoodAnalysisRepository;
import backend.analysis.service.PostTagService;
import backend.post.model.dto.FeedPage;
import backend.post.model.dto.PostSummary;
import backend.post.model.entity.Post;
import backend.post.model.repository.PostRepository;

// 피드와 검색의 음식 필터(JM-83)를 실제 요청과 실제 DB(MySQL)로 확인한다.
// 개발 DB에 태그가 붙은 다른 글이 있어도 되도록, 찾은 글 가운데 이 테스트가 만든 글만 보거나 (mine)
// 무작위 표시 글자(mark)를 닉네임에 넣고 검색어와 함께 거른다.
// @Transactional: 테스트가 저장한 글, 분석 기록, 태그는 끝나면 되돌린다
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PostFilterTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private FoodAnalysisRepository foodAnalysisRepository;

	@Autowired
	private PostTagService postTagService;

	private final String mark = UUID.randomUUID().toString().replaceAll("[^a-f]", "").concat("abcdef").substring(0, 6);

	// 같은 묶음 안은 "하나라도", 묶음끼리는 "모두" (운영자 결정 11-24)
	@Test
	void 같은_묶음은_하나라도_다른_묶음끼리는_모두_맞아야_한다() throws Exception {
		Long koreanSpicy = saveTagged(500, FoodTag.KOREAN, FoodTag.SPICY);
		Long chineseSpicy = saveTagged(500, FoodTag.CHINESE, FoodTag.SPICY);
		Long koreanMild = saveTagged(500, FoodTag.KOREAN, FoodTag.NOT_SPICY);
		Long japaneseSpicy = saveTagged(500, FoodTag.JAPANESE, FoodTag.SPICY);

		List<Long> found = mine(feed(get("/").param("tag", "KOREAN", "CHINESE", "SPICY")), koreanSpicy, chineseSpicy, koreanMild, japaneseSpicy);

		assertThat(found).containsExactly(chineseSpicy, koreanSpicy);
	}

	// 보이는 칼로리(확정한 글은 확정한 합계)가 최소 이상 최대 미만. 분석이 없는 글은 칼로리를 고르면 빠진다
	@Test
	void 칼로리는_최소_이상_최대_미만이고_확정한_글은_확정한_칼로리로_본다() throws Exception {
		Long at400 = saveTagged(400);
		Long at799 = saveTagged(799.9);
		Long at800 = saveTagged(800);
		Long confirmedDown = saveTagged(1500);
		foodAnalysisRepository.confirmOnce(confirmedDown, "[]", new BigDecimal("600"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
				false, Instant.now());
		Long noAnalysis = postRepository.save(new Post("필터" + mark, "hash", null, "x.jpg", "x-t.jpg", null)).getId();

		List<Long> found = mine(feed(get("/").param("kcalMin", "400").param("kcalMax", "800")),
				at400, at799, at800, confirmedDown, noAnalysis);
		assertThat(found).containsExactly(confirmedDown, at799, at400);

		List<Long> over = mine(feed(get("/").param("kcalMin", "800")), at400, at799, at800, confirmedDown, noAnalysis);
		assertThat(over).containsExactly(at800);
	}

	@Test
	void 검색어와_필터를_함께_쓰면_둘_다_맞는_글이다() throws Exception {
		Long spicy = saveTagged(500, FoodTag.SPICY);
		saveTagged(500, FoodTag.NOT_SPICY);

		MvcResult result = mockMvc.perform(get("/search").param("q", mark).param("tag", "SPICY"))
				.andExpect(status().isOk())
				.andExpect(model().attribute("total", 1L))
				.andExpect(model().attribute("filtered", true))
				.andExpect(model().attribute("pagerBase", "/search?q=" + mark + "&amp;tag=SPICY&amp;page="))
				.andExpect(model().attribute("clearFilterUrl", "/search?q=" + mark))
				.andReturn();

		assertThat(idsOf(feedOf(result))).containsExactly(spicy);
	}

	@Test
	void 지운_글은_거르지_않고_빼고_쪽은_24개씩_나눈다() throws Exception {
		Long deleted = saveTagged(500, FoodTag.DRINK);
		Post post = postRepository.findById(deleted).orElseThrow();
		post.deleteByAuthor();
		postRepository.saveAndFlush(post);
		for (int i = 0; i < 25; i++) {
			saveTagged(500, FoodTag.DRINK);
		}

		MvcResult page1 = mockMvc.perform(get("/search").param("q", mark).param("tag", "DRINK"))
				.andExpect(model().attribute("total", 25L)).andReturn();
		assertThat(feedOf(page1).posts()).hasSize(24);
		assertThat(feedOf(page1).nav().totalPages()).isEqualTo(2);
		MvcResult page2 = mockMvc.perform(get("/search").param("q", mark).param("tag", "DRINK").param("page", "9")).andReturn();
		assertThat(feedOf(page2).nav().page()).isEqualTo(2);
		assertThat(idsOf(feedOf(page2))).hasSize(1).doesNotContain(deleted);
	}

	@Test
	void 피드의_필터는_쪽_번호_주소에_실리고_모르는_값만_있으면_보통_피드다() throws Exception {
		mockMvc.perform(get("/").param("tag", "KOREAN").param("kcalMax", "600"))
				.andExpect(status().isOk())
				.andExpect(model().attribute("filtered", true))
				.andExpect(model().attribute("pagerBase", "/?tag=KOREAN&amp;kcalMax=600&amp;page="));

		mockMvc.perform(get("/").param("tag", "PIZZA").param("kcalMin", "450"))
				.andExpect(status().isOk())
				.andExpect(model().attribute("filtered", false))
				.andExpect(model().attribute("pagerBase", "/?page="))
				.andExpect(model().attributeDoesNotExist("total"));
	}

	// 검색 칸을 비운 채 찾으면 피드로 가되, 고른 필터는 그대로 싣는다
	@Test
	void 검색어가_비면_고른_필터를_싣고_피드로_보낸다() throws Exception {
		mockMvc.perform(get("/search").param("q", " ").param("tag", "MEAL").param("kcalMin", "200"))
				.andExpect(redirectedUrl("/?tag=MEAL&kcalMin=200"));
	}

	// 글 하나: 닉네임에 mark, 분석은 DONE에 총 칼로리 kcal, 태그 tags
	private Long saveTagged(double kcal, FoodTag... tags) {
		Post post = postRepository.save(new Post("필터" + mark, "hash", null, "x.jpg", "x-t.jpg", null));
		FoodAnalysis analysis = new FoodAnalysis(post);
		FoodAnalysisResult result = new FoodAnalysisResult(true, List.of(new FoodItem("음식", 100, kcal, 10, 10, 10)), "HIGH", "");
		analysis.markDone(new FoodAnalyzerResponse(result, "test-model", 100, 10), "[{\"name\":\"음식\"}]", 1000);
		foodAnalysisRepository.saveAndFlush(analysis);
		if (tags.length > 0) {
			postTagService.replace(post.getId(), Set.of(tags));
		}
		return post.getId();
	}

	private FeedPage feed(MockHttpServletRequestBuilder request) throws Exception {
		return feedOf(mockMvc.perform(request).andExpect(status().isOk()).andReturn());
	}

	private static FeedPage feedOf(MvcResult result) {
		return (FeedPage) result.getModelAndView().getModel().get("feed");
	}

	private static List<Long> idsOf(FeedPage feed) {
		return feed.posts().stream().map(PostSummary::id).toList();
	}

	// 찾은 글 가운데 이 테스트가 만든 글만, 찾은 순서대로 (개발 DB의 다른 글은 뺀다). 피드는 24개씩이라 한 쪽 안에 있어야 한다
	private static List<Long> mine(FeedPage feed, Long... ids) {
		Set<Long> own = Set.of(ids);
		return idsOf(feed).stream().filter(own::contains).toList();
	}
}
