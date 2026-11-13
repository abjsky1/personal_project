package backend.post.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import backend.analysis.model.dto.FoodAnalysisResult;
import backend.analysis.model.dto.FoodAnalysisView;
import backend.analysis.model.dto.FoodAnalysisView.FoodRow;
import backend.analysis.model.dto.FoodAnalyzerResponse;
import backend.analysis.model.dto.FoodCandidates;
import backend.analysis.model.dto.FoodCandidates.Candidate;
import backend.analysis.model.dto.FoodCandidates.CandidateItem;
import backend.analysis.model.dto.TypedEstimates;
import backend.analysis.model.dto.TypedEstimates.TypedEstimate;
import backend.analysis.model.dto.TypedEstimatesResponse;
import backend.analysis.model.entity.FoodAnalysis;
import backend.analysis.model.repository.FoodAnalysisRepository;
import backend.analysis.service.FoodAnalysisException;
import backend.analysis.service.FoodAnalyzer;
import backend.common.error.ErrorCode;
import backend.image.TestImages;
import backend.post.model.entity.Post;
import backend.post.model.repository.PostRepository;
import tools.jackson.databind.json.JsonMapper;

// 고쳐 쓰는 AI (JM-74)의 두 가지 약속을 실제 요청과 실제 DB로 확인하는 테스트
// - 확정은 글마다 한 번뿐이다
// - 후보에 없는 음식은 이름을 직접 적을 수 있고, 그때 AI를 부르는 것도 글마다 한 번뿐이다
//
// @MockitoBean: Spring이 만든 진짜 객체(GeminiFoodAnalyzer) 대신 가짜를 넣는다.
// → 직접 적은 음식을 계산하는 호출이 실제 Gemini로 나가지 않고, AI의 답을 테스트가 정한다.
// @Transactional, @TestPropertySource는 PostControllerTest와 같다 (끝나면 DB를 되돌리고, 사진은 build/test-uploads에 둔다).
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
		"app.upload-dir=build/test-uploads",
		"app.rate-limit.upload-per-minute=1000",
		"app.rate-limit.upload-per-day=1000" })
class PostConfirmTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private FoodAnalysisRepository foodAnalysisRepository;

	@Autowired
	private JsonMapper jsonMapper;

	@MockitoBean
	private FoodAnalyzer foodAnalyzer;

	private final MockMultipartFile photo = new MockMultipartFile("image", "food.jpg", "image/jpeg", TestImages.jpeg(300, 200));

	// 이 글을 올린 브라우저 (세션). 이 세션으로 보내면 비밀번호 없이 확정할 수 있다.
	private final MockHttpSession uploader = new MockHttpSession();

	// 음식 둘(찌개 400g, 밥 210g)에 후보 셋씩
	private static final List<CandidateItem> TWO_FOODS = List.of(
			new CandidateItem(400, List.of(
					new Candidate("김치찌개", 55, 3, 4.5, 2.5),
					new Candidate("부대찌개", 85, 5, 5, 5),
					new Candidate("순두부찌개", 48, 2, 4.3, 2.5))),
			new CandidateItem(210, List.of(
					new Candidate("공기밥", 148, 32.4, 2.7, 0.3),
					new Candidate("잡곡밥", 150, 31.8, 3.5, 0.6),
					new Candidate("볶음밥", 185, 27, 4.2, 6.4))));

	// 음식 하나(볶음밥 350g)에 후보 셋
	private static final List<CandidateItem> ONE_FOOD = List.of(
			new CandidateItem(350, List.of(
					new Candidate("김치볶음밥", 180, 26, 5, 6),
					new Candidate("볶음밥", 185, 27, 4.2, 6.4),
					new Candidate("비빔밥", 120, 18, 4, 3))));

	// ---- 확정은 한 번만 ----

	@Test
	void 확정은_한_번만_할_수_있고_다시_보내도_값이_바뀌지_않는다() throws Exception {
		Post post = uploadAndAnalyze(TWO_FOODS);
		String url = "/posts/" + post.getId();

		// 첫 음식은 둘째 후보(부대찌개) 300g, 둘째 음식은 첫 후보(공기밥) 105g → 255 + 155.4
		mockMvc.perform(post(url + "/confirm").session(uploader).param("pick", "1", "0").param("amount", "300", "105"))
				.andExpect(redirectedUrl(url))
				.andExpect(flash().attributeCount(0));

		// 확정한 글의 상세에는 다시 고칠 칸이 없다
		FoodAnalysisView view = viewOf(url);
		assertThat(view.confirmed()).isTrue();
		// AI의 답과 확정한 값이 함께 넘어간다
		assertThat(view.aiTotals().kcal()).isEqualByComparingTo("530.8");
		assertThat(view.confirmedTotals().kcal()).isEqualByComparingTo("410.4");
		assertThat(view.confirmRows()).isEmpty();

		// 화면을 거치지 않고 다른 값으로 한 번 더 보내도 받지 않는다 (에러 화면, 409)
		mockMvc.perform(post(url + "/confirm").session(uploader).param("pick", "0", "2").param("amount", "400", "210"))
				.andExpect(status().isConflict())
				.andExpect(view().name("common/error"));

		FoodAnalysis saved = analysisOf(post);
		assertThat(saved.getConfirmedTotalKcal()).isEqualByComparingTo("410.4");
		assertThat(saved.getConfirmedItems()).contains("부대찌개").doesNotContain("볶음밥");
		// 후보에서만 골랐으므로 AI를 다시 부르지 않았고, 직접 적기도 쓰지 않았다
		assertThat(saved.getTypedCalledAt()).isNull();
		assertThat(saved.getTypedMismatch()).isFalse();
		verify(foodAnalyzer, never()).estimateTyped(any(), any());
	}

	@Test
	void 값을_쓰는_UPDATE들은_조건이_맞을_때만_한_번_쓰인다() throws Exception {
		Post post = uploadAndAnalyze(TWO_FOODS);
		Long id = post.getId();

		// 직접 적기 한 번: 처음에만 차지할 수 있다. 돌려주면 다시 차지할 수 있다.
		assertThat(foodAnalysisRepository.claimTyped(id, Instant.now())).isEqualTo(1);
		assertThat(foodAnalysisRepository.claimTyped(id, Instant.now())).isZero();
		assertThat(foodAnalysisRepository.releaseTyped(id)).isEqualTo(1);
		assertThat(analysisOf(post).isTypedUsed()).isFalse();
		assertThat(foodAnalysisRepository.claimTyped(id, Instant.now())).isEqualTo(1);
		assertThat(foodAnalysisRepository.recordTypedCall(id, 1500, 40, 2100)).isEqualTo(1);

		// 확정: 처음에만 쓰인다. 두 번째는 0이 돌아오고 값은 그대로다.
		BigDecimal zero = new BigDecimal("0.0");
		assertThat(foodAnalysisRepository.confirmOnce(id, "[]", new BigDecimal("100.0"), zero, zero, zero, false, Instant.now())).isEqualTo(1);
		assertThat(foodAnalysisRepository.confirmOnce(id, "[]", new BigDecimal("999.0"), zero, zero, zero, true, Instant.now())).isZero();

		FoodAnalysis saved = analysisOf(post);
		assertThat(saved.getConfirmedTotalKcal()).isEqualByComparingTo("100.0");
		assertThat(saved.getTypedMismatch()).isFalse();
		assertThat(saved.isTypedUsed()).isTrue();
		assertThat(saved.getTypedInputTokens()).isEqualTo(1500);
		assertThat(saved.getTypedLatencyMs()).isEqualTo(2100);

		// 확정한 뒤에는 직접 적기를 돌려주거나 다시 차지할 수 없다
		assertThat(foodAnalysisRepository.releaseTyped(id)).isZero();
		assertThat(foodAnalysisRepository.claimTyped(id, Instant.now())).isZero();
	}

	// ---- 후보에 없어서 이름을 직접 적기 ----

	@Test
	void 직접_적은_음식은_AI가_계산한_값으로_확정되고_이름에_쉼표가_있어도_된다() throws Exception {
		// 음식이 하나뿐인 글: 이름 칸의 값도 하나만 온다. 그 값에 쉼표가 있어도 둘로 나뉘면 안 된다.
		Post post = uploadAndAnalyze(ONE_FOOD);
		String url = "/posts/" + post.getId();
		aiEstimates(List.of("밥, 계란후라이"), new TypedEstimate(true, true, 160, 20, 6, 6));

		mockMvc.perform(post(url + "/confirm").session(uploader)
				.param("pick", "typed")
				.param("amount", "400")
				.param("name", "밥, 계란후라이"))
				.andExpect(redirectedUrl(url))
				.andExpect(flash().attributeCount(0));

		// 100g당 160kcal × 400g = 640
		FoodAnalysis saved = analysisOf(post);
		assertThat(saved.isConfirmed()).isTrue();
		assertThat(saved.getConfirmedTotalKcal()).isEqualByComparingTo("640.0");
		assertThat(saved.getConfirmedItems()).contains("TYPED", "밥, 계란후라이");
		assertThat(saved.getTypedMismatch()).isFalse();
		// AI의 첫 답은 그대로: 김치볶음밥 100g당 180kcal × 350g = 630
		assertThat(saved.getTotalKcal()).isEqualByComparingTo("630.0");
		// 직접 적은 음식을 계산한 호출의 기록
		assertThat(saved.getTypedCalledAt()).isNotNull();
		assertThat(saved.getTypedInputTokens()).isEqualTo(1500);
		assertThat(saved.getTypedOutputTokens()).isEqualTo(40);

		// 상세 화면에는 AI가 본 음식과 직접 적은 음식이 한 줄로 짝지어 넘어간다
		FoodAnalysisView view = viewOf(url);
		assertThat(view.rows()).hasSize(1);
		assertThat(view.rows().get(0).change()).isEqualTo(FoodRow.CHANGED);
		assertThat(view.rows().get(0).ai().name()).isEqualTo("김치볶음밥");
		assertThat(view.rows().get(0).confirmed().name()).isEqualTo("밥, 계란후라이");
		assertThat(view.aiTotals().kcal()).isEqualByComparingTo("630.0");
		assertThat(view.confirmedTotals().kcal()).isEqualByComparingTo("640.0");
	}

	@Test
	void 음식이_하나뿐인_글은_이름_칸이_비어_와도_후보에서_골라_확정된다() throws Exception {
		Post post = uploadAndAnalyze(ONE_FOOD);
		String url = "/posts/" + post.getId();

		// 화면은 직접 적지 않은 줄의 이름 칸도 빈 값으로 함께 보낸다
		mockMvc.perform(post(url + "/confirm").session(uploader)
				.param("pick", "2")
				.param("amount", "300")
				.param("name", ""))
				.andExpect(redirectedUrl(url));

		// 비빔밥 100g당 120kcal × 300g = 360
		assertThat(analysisOf(post).getConfirmedTotalKcal()).isEqualByComparingTo("360.0");
		verify(foodAnalyzer, never()).estimateTyped(any(), any());
	}

	@Test
	void 직접_적은_글자가_음식이_아니면_확정되지_않고_그_뒤로는_후보에서만_고를_수_있다() throws Exception {
		Post post = uploadAndAnalyze(TWO_FOODS);
		String url = "/posts/" + post.getId();
		aiEstimates(List.of("자동차"), new TypedEstimate(false, false, 0, 0, 0, 0));

		mockMvc.perform(post(url + "/confirm").session(uploader)
				.param("pick", "typed", "0")
				.param("amount", "300", "210")
				.param("name", "자동차", ""))
				// 확정 칸 자리로 돌아가서 메시지를 보여 주고, 고르던 값을 다시 채운다
				.andExpect(redirectedUrl(url + "#analysis-confirm"))
				.andExpect(flash().attribute("confirmError", ErrorCode.TYPED_NOT_FOOD.getMessage()))
				.andExpect(flash().attribute("confirmPicks", List.of("typed", "0")))
				.andExpect(flash().attribute("confirmAmounts", List.of("300", "210")))
				.andExpect(flash().attribute("confirmNames", List.of("자동차", "")));

		// 확정되지 않았고, 직접 적기 한 번은 쓴 것으로 남는다 (AI가 답을 줬다)
		FoodAnalysis afterTyped = analysisOf(post);
		assertThat(afterTyped.isConfirmed()).isFalse();
		assertThat(afterTyped.isTypedUsed()).isTrue();
		FoodAnalysisView view = viewOf(url);
		assertThat(view.typedAllowed()).isFalse();
		assertThat(view.confirmRows()).hasSize(2);

		// 다시 직접 적어 보내도 AI를 또 부르지 않는다
		mockMvc.perform(post(url + "/confirm").session(uploader)
				.param("pick", "typed", "0")
				.param("amount", "300", "210")
				.param("name", "마라샹궈", ""))
				.andExpect(redirectedUrl(url + "#analysis-confirm"))
				.andExpect(flash().attribute("confirmError", ErrorCode.TYPED_ALREADY_USED.getMessage()));
		verify(foodAnalyzer, times(1)).estimateTyped(any(), any());

		// 후보에서 골라서는 확정할 수 있다
		mockMvc.perform(post(url + "/confirm").session(uploader).param("pick", "1", "0").param("amount", "300", "105"))
				.andExpect(redirectedUrl(url));
		assertThat(analysisOf(post).getConfirmedTotalKcal()).isEqualByComparingTo("410.4");
	}

	@Test
	void 글쓴이가_아니면_직접_적은_음식을_AI에게_묻지_않는다() throws Exception {
		Post post = uploadAndAnalyze(TWO_FOODS);
		String url = "/posts/" + post.getId();

		// 올린 브라우저가 아닌 곳에서(세션 없이) 틀린 비밀번호로 보낸다
		mockMvc.perform(post(url + "/confirm")
				.param("password", "0000")
				.param("pick", "typed", "0")
				.param("amount", "300", "210")
				.param("name", "마라샹궈", ""))
				.andExpect(redirectedUrl(url + "#analysis-confirm"))
				.andExpect(flash().attribute("confirmError", ErrorCode.INVALID_PASSWORD.getMessage()))
				// 적던 이름은 다시 채워 준다
				.andExpect(flash().attribute("confirmNames", List.of("마라샹궈", "")));

		// 비밀번호 확인이 먼저다: 남이 AI를 부르게 하거나, 글쓴이의 직접 적기 한 번을 써 버릴 수 없다
		verify(foodAnalyzer, never()).estimateTyped(any(), any());
		assertThat(analysisOf(post).isTypedUsed()).isFalse();
		assertThat(analysisOf(post).isConfirmed()).isFalse();
	}

	@Test
	void 사진과_맞지_않는다고_본_음식도_확정되고_표시가_남는다() throws Exception {
		Post post = uploadAndAnalyze(TWO_FOODS);
		String url = "/posts/" + post.getId();
		aiEstimates(List.of("초코케이크"), new TypedEstimate(true, false, 370, 50, 5, 17));

		mockMvc.perform(post(url + "/confirm").session(uploader)
				.param("pick", "typed", "-1")
				.param("amount", "100", "")
				.param("name", "초코케이크", ""))
				.andExpect(redirectedUrl(url));

		FoodAnalysis saved = analysisOf(post);
		assertThat(saved.getConfirmedTotalKcal()).isEqualByComparingTo("370.0");
		assertThat(saved.getTypedMismatch()).isTrue();
	}

	@Test
	void AI에게서_답을_받지_못하면_직접_적기_한_번을_돌려주고_다시_적어_확정할_수_있다() throws Exception {
		Post post = uploadAndAnalyze(TWO_FOODS);
		String url = "/posts/" + post.getId();
		// 첫 번째 호출은 AI 서버가 혼잡해서 실패하고, 두 번째 호출은 답한다
		when(foodAnalyzer.estimateTyped(any(), eq(List.of("마라샹궈"))))
				.thenThrow(new FoodAnalysisException("HTTP_503"))
				.thenReturn(new TypedEstimatesResponse(
						new TypedEstimates(List.of(new TypedEstimate(true, true, 150, 8, 9, 9))), "test-model", 1500, 40));

		mockMvc.perform(post(url + "/confirm").session(uploader)
				.param("pick", "typed", "0")
				.param("amount", "300", "210")
				.param("name", "마라샹궈", ""))
				.andExpect(redirectedUrl(url + "#analysis-confirm"))
				.andExpect(flash().attribute("confirmError", ErrorCode.TYPED_AI_UNAVAILABLE.getMessage()));

		// 분석이 이루어지지 않았으므로 직접 적기는 아직 쓰지 않은 것이다
		assertThat(analysisOf(post).isTypedUsed()).isFalse();
		assertThat(viewOf(url).typedAllowed()).isTrue();

		mockMvc.perform(post(url + "/confirm").session(uploader)
				.param("pick", "typed", "0")
				.param("amount", "300", "210")
				.param("name", "마라샹궈", ""))
				.andExpect(redirectedUrl(url));

		// 마라샹궈 100g당 150kcal × 300g = 450, 공기밥 310.8
		assertThat(analysisOf(post).getConfirmedTotalKcal()).isEqualByComparingTo("760.8");
		verify(foodAnalyzer, times(2)).estimateTyped(any(), any());
	}

	// "내가 보고 확정"으로 글을 실제로 올리고(사진 파일이 저장된다), 분석이 끝나 후보가 있는 상태로 만든다.
	// 올리는 요청을 uploader 세션으로 보내서, 그 세션은 비밀번호 없이 확정할 수 있다.
	// (테스트에서는 뒤에서 도는 분석이 아직 확정되지 않은 이 글을 보지 못해서 PENDING으로 남는다. 그래서 직접 끝난 상태로 만든다)
	private Post uploadAndAnalyze(List<CandidateItem> items) throws Exception {
		mockMvc.perform(multipart("/posts").file(photo).session(uploader)
				.param("nickname", "테스터")
				.param("password", "1234")
				.param("confirmMode", "USER"))
				.andExpect(status().is3xxRedirection());
		Post post = postRepository.findAll(Sort.by(Sort.Direction.DESC, "id")).get(0);

		FoodAnalysis analysis = analysisOf(post);
		FoodAnalysisResult first = new FoodCandidates(true, items, "MEDIUM", "1인분 기준").firstAnswer();
		analysis.markDone(new FoodAnalyzerResponse(first, "test-model", 1400, 300), jsonMapper.writeValueAsString(first.items()), 3000);
		analysis.keepCandidates(jsonMapper.writeValueAsString(items));
		foodAnalysisRepository.save(analysis);
		return post;
	}

	private FoodAnalysis analysisOf(Post post) {
		return foodAnalysisRepository.findById(post.getId()).orElseThrow();
	}

	// 상세 화면으로 넘어가는 분석 결과
	private FoodAnalysisView viewOf(String url) throws Exception {
		MvcResult detail = mockMvc.perform(get(url).session(uploader)).andExpect(status().isOk()).andReturn();
		return (FoodAnalysisView) detail.getModelAndView().getModel().get("analysis");
	}

	// names를 물으면 AI가 estimates로 답하게 한다 (사진은 무엇이 오든)
	private void aiEstimates(List<String> names, TypedEstimate... estimates) {
		when(foodAnalyzer.estimateTyped(any(), eq(names)))
				.thenReturn(new TypedEstimatesResponse(new TypedEstimates(List.of(estimates)), "test-model", 1500, 40));
	}
}
