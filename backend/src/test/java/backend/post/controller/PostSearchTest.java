package backend.post.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import backend.analysis.model.dto.ConfirmedItem;
import backend.analysis.model.dto.FoodAnalysisResult;
import backend.analysis.model.dto.FoodAnalysisResult.FoodItem;
import backend.analysis.model.dto.FoodAnalyzerResponse;
import backend.analysis.model.entity.FoodAnalysis;
import backend.analysis.model.repository.FoodAnalysisRepository;
import backend.post.model.dto.FeedPage;
import backend.post.model.dto.PostSummary;
import backend.post.model.entity.Post;
import backend.post.model.repository.PostRepository;
import backend.post.service.PostService;

// 검색(JM-52)을 실제 요청과 실제 DB(MySQL)로 확인한다. 음식 이름 찾기는 MySQL의 JSON_TABLE을 쓰므로 DB 없이는 확인할 수 없다.
// 개발 DB에 다른 글이 있어도 되도록, 테스트마다 무작위 표시 글자(mark)를 이름에 붙이고 그 글자로 찾는다.
// @Transactional: 테스트가 저장한 글과 분석 기록은 끝나면 되돌린다
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PostSearchTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private FoodAnalysisRepository foodAnalysisRepository;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	// 이 테스트만의 표시 글자 (영어 소문자 6자. 닉네임은 20자까지라 짧게)
	private final String mark = UUID.randomUUID().toString().replaceAll("[^a-f]", "").concat("abcdef").substring(0, 6);

	@Test
	void 닉네임의_일부로_찾고_영어는_대소문자를_가리지_않는다() throws Exception {
		Post post = savePost("Ramen" + mark, null);
		savePost("다른사람", null);

		assertThat(idsOf(search("amen" + mark))).containsExactly(post.getId());
		assertThat(idsOf(search("RAMEN" + mark.toUpperCase()))).containsExactly(post.getId());
	}

	@Test
	void AI가_낸_음식_이름으로_찾는다() throws Exception {
		Post post = savePost("테스터", null);
		saveDone(post, "김치찌개" + mark, "공기밥");

		assertThat(idsOf(search("찌개" + mark))).containsExactly(post.getId());
	}

	@Test
	void 글쓴이가_확정한_음식_이름으로도_찾는다() throws Exception {
		Post post = savePost("테스터", null);
		saveDone(post, "된장국");
		String confirmed = jsonMapper.writeValueAsString(List.of(
				ConfirmedItem.typed(new FoodItem("현미밥" + mark, 150, 240, 49.5, 4.5, 1.8), true)));
		foodAnalysisRepository.confirmOnce(post.getId(), confirmed, new BigDecimal("240"), new BigDecimal("49.5"),
				new BigDecimal("4.5"), new BigDecimal("1.8"), false, Instant.now());

		assertThat(idsOf(search("현미밥" + mark))).containsExactly(post.getId());
	}

	// 운영자 결정 (11-23): 메모는 찾지 않는다. 음식 이름 칸만 보므로 JSON의 칸 이름(kcal)이나 확정 방식(TYPED)으로도 걸리지 않는다
	@Test
	void 메모와_JSON의_칸_이름으로는_찾지_않는다() throws Exception {
		Post post = savePost("테스터", "오늘 점심 " + mark);
		saveDone(post, "비빔밥");

		assertThat(search(mark).posts()).isEmpty();
		assertThat(idsOf(search("kcal"))).doesNotContain(post.getId());
		assertThat(idsOf(search("amountG"))).doesNotContain(post.getId());
	}

	@Test
	void 지운_글은_찾지_않는다() throws Exception {
		Post kept = savePost("남긴글" + mark, null);
		Post deleted = savePost("지운글" + mark, null);
		deleted.deleteByAuthor();
		postRepository.saveAndFlush(deleted);

		assertThat(idsOf(search(mark))).containsExactly(kept.getId());
	}

	// LIKE에서 %와 _는 "아무 글자"라는 뜻이다. 검색어의 %와 _는 그 글자 자체로 찾아야 한다 ("%"만 쳐도 모든 글이 나오면 안 된다)
	@Test
	void 퍼센트와_밑줄과_느낌표는_그_글자_그대로_찾는다() throws Exception {
		Post underscore = savePost("a_b" + mark, null);
		savePost("axb" + mark, null);
		Post percent = savePost("c%d" + mark, null);
		savePost("cxxd" + mark, null);
		Post bang = savePost("e!f" + mark, null);

		assertThat(idsOf(search("a_b" + mark))).containsExactly(underscore.getId());
		assertThat(idsOf(search("c%d" + mark))).containsExactly(percent.getId());
		assertThat(idsOf(search("e!f" + mark))).containsExactly(bang.getId());
	}

	@Test
	void 최신_글부터_24개씩_쪽을_나누고_범위_밖_번호는_끝_쪽으로_맞춘다() throws Exception {
		Post first = null;
		Post latest = null;
		for (int i = 0; i < 25; i++) {
			latest = savePost("쪽" + mark, null);
			if (first == null) {
				first = latest;
			}
		}

		MvcResult page1 = mockMvc.perform(get("/search").param("q", mark)).andExpect(status().isOk())
				.andExpect(model().attribute("total", 25L)).andReturn();
		FeedPage feed = feedOf(page1);
		assertThat(feed.posts()).hasSize(24);
		assertThat(feed.posts().get(0).id()).isEqualTo(latest.getId());
		assertThat(feed.nav().totalPages()).isEqualTo(2);

		FeedPage page2 = search(mark, "2");
		assertThat(idsOf(page2)).containsExactly(first.getId());
		assertThat(search(mark, "999").nav().page()).isEqualTo(2);
		assertThat(search(mark, "0").nav().page()).isEqualTo(1);
	}

	// 분석 기록의 JSON이 깨져 있으면 JSON_TABLE이 쿼리 전체를 실패시킨다. 깨진 기록은 음식이 없는 것으로 보고 넘어가야 한다
	@Test
	void 음식_기록이_깨진_글이_있어도_검색은_된다() throws Exception {
		Post broken = savePost("깨진글" + mark, null);
		saveDone(broken, "라면");
		jdbcTemplate.update("UPDATE food_analyses SET items = 'not json', confirmed_items = '[{' WHERE post_id = ?", broken.getId());
		Post fine = savePost("테스터", null);
		saveDone(fine, "라면" + mark);

		assertThat(idsOf(search(mark))).containsExactly(fine.getId(), broken.getId());
	}

	@Test
	void 화면에는_검색어와_주소에_넣을_검색어를_넘긴다() throws Exception {
		savePost("검색어 " + mark, null);

		mockMvc.perform(get("/search").param("q", "  검색어 " + mark + "  "))
				.andExpect(status().isOk())
				.andExpect(view().name("posts/list"))
				.andExpect(model().attribute("query", "검색어 " + mark))
				.andExpect(model().attribute("searchParam", "%EA%B2%80%EC%83%89%EC%96%B4+" + mark))
				.andExpect(model().attribute("total", 1L));
	}

	@Test
	void 검색어가_없거나_빈칸뿐이면_피드로_보낸다() throws Exception {
		mockMvc.perform(get("/search")).andExpect(redirectedUrl("/"));
		mockMvc.perform(get("/search").param("q", "   ")).andExpect(redirectedUrl("/"));
	}

	@Test
	void 검색어는_앞뒤_빈칸을_떼고_30자까지만_쓴다() {
		assertThat(PostService.normalizeQuery(null)).isEmpty();
		assertThat(PostService.normalizeQuery("  라면 ")).isEqualTo("라면");
		assertThat(PostService.normalizeQuery("가".repeat(40))).hasSize(30);
		// 이모지는 두 칸(char 2개)을 쓰지만 한 글자로 센다. 반만 잘리지 않는다
		String emoji = "🍜";
		assertThat(PostService.normalizeQuery(emoji.repeat(31))).isEqualTo(emoji.repeat(30));
	}

	private FeedPage search(String q) throws Exception {
		return search(q, "1");
	}

	private FeedPage search(String q, String page) throws Exception {
		return feedOf(mockMvc.perform(get("/search").param("q", q).param("page", page)).andExpect(status().isOk()).andReturn());
	}

	private static FeedPage feedOf(MvcResult result) {
		return (FeedPage) result.getModelAndView().getModel().get("feed");
	}

	private static List<Long> idsOf(FeedPage feed) {
		return feed.posts().stream().map(PostSummary::id).toList();
	}

	// 테스트용 글을 DB에 바로 저장한다 (사진 파일은 만들지 않고 이름만, 비밀번호는 검색과 상관없어 아무 값)
	private Post savePost(String nickname, String memo) {
		return postRepository.save(new Post(nickname, "hash", memo, "test.jpg", "test-thumb.jpg", null));
	}

	// AI가 음식 이름들을 낸 분석 기록 (서버가 저장하는 것과 같은 JSON 모양)
	private void saveDone(Post post, String... names) {
		List<FoodItem> foods = Arrays.stream(names).map(name -> new FoodItem(name, 100, 200, 30, 5, 3)).toList();
		FoodAnalysis analysis = new FoodAnalysis(post);
		analysis.markDone(new FoodAnalyzerResponse(new FoodAnalysisResult(true, foods, "HIGH", ""), "test-model", 100, 10),
				jsonMapper.writeValueAsString(foods), 1000);
		foodAnalysisRepository.saveAndFlush(analysis);
	}
}
