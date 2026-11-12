package backend.post.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import backend.analysis.model.dto.FoodAnalysisResult;
import backend.analysis.model.dto.FoodAnalysisResult.FoodItem;
import backend.analysis.model.dto.FoodAnalyzerResponse;
import backend.analysis.model.entity.AnalysisStatus;
import backend.analysis.model.entity.FoodAnalysis;
import backend.analysis.model.repository.FoodAnalysisRepository;
import backend.common.error.ErrorCode;
import backend.image.TestImages;
import backend.location.model.entity.PostLocation;
import backend.location.model.repository.PostLocationRepository;
import backend.post.model.dto.FeedPage;
import backend.post.model.dto.PostSummary;
import backend.post.model.entity.Post;
import backend.post.model.entity.PostStatus;
import backend.post.model.repository.PostRepository;

// 글쓰기 요청을 실제로 보내 보고, 저장과 입력 검사가 제대로 되는지 확인하는 테스트
//
// @Transactional: 테스트 하나가 끝나면 그 테스트에서 DB에 저장한 내용을 모두 되돌린다.
// → 로컬에서 돌려도 내 개발 DB에 테스트 글이 쌓이지 않는다.
// @TestPropertySource: 이 테스트에서만 설정을 바꾼다.
// - 테스트로 올린 사진은 uploads/ 대신 build/test-uploads/에 저장한다.
// - 이 테스트는 같은 IP에서 여러 번 올리므로, 업로드 횟수 제한을 넉넉하게 푼다. (제한 자체는 UploadRateLimitTest에서 검사)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
		"app.upload-dir=build/test-uploads",
		"app.rate-limit.upload-per-minute=1000",
		"app.rate-limit.upload-per-day=1000" })
class PostControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private PostLocationRepository postLocationRepository;

	@Autowired
	private FoodAnalysisRepository foodAnalysisRepository;

	// 테스트용 사진 파일: (form의 칸 이름, 파일 이름, 형식, 내용)
	// 서버가 파일 내용을 진짜 사진인지 검사하므로, 코드로 만든 실제 JPEG(300 × 200)를 넣는다.
	private final MockMultipartFile photo = new MockMultipartFile("image", "food.jpg", "image/jpeg", TestImages.jpeg(300, 200));

	// 브라우저(사람)가 보내는 User-Agent의 예. 조회수는 사람이 연 것만 센다 (JM-72)
	private static final String BROWSER =
			"Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 Safari/604.1";

	// GPS(서울시청 근처)와 촬영 시각(한국 시간 2026-10-29 12:30)이 들어 있는 사진
	private final MockMultipartFile photoWithGps = new MockMultipartFile("image", "food.jpg", "image/jpeg",
			TestImages.withGps(TestImages.jpeg(300, 200), 37.566535, 126.977969, "2026:10:29 12:30:00"));

	@Test
	void 위치_제공에_동의하면_위치와_촬영_시각을_저장한다() throws Exception {
		mockMvc.perform(multipart("/posts").file(photoWithGps)
				.param("nickname", "테스터")
				.param("password", "1234")
				.param("locationConsent", "true"))
				.andExpect(status().is3xxRedirection());

		Post saved = latestPost();
		assertThat(saved.getPhotoTakenAt()).isEqualTo(Instant.parse("2026-10-29T03:30:00Z"));

		PostLocation location = postLocationRepository.findById(saved.getId()).orElseThrow();
		assertThat(location.getPoint().latitude()).isEqualByComparingTo("37.566535");
		assertThat(location.getPoint().longitude()).isEqualByComparingTo("126.977969");
		assertThat(location.getSource()).isEqualTo("EXIF");
	}

	@Test
	void 동의하지_않으면_사진에_위치가_있어도_저장하지_않는다() throws Exception {
		// locationConsent를 보내지 않음 = 체크박스를 체크하지 않음
		mockMvc.perform(multipart("/posts").file(photoWithGps)
				.param("nickname", "테스터")
				.param("password", "1234"))
				.andExpect(status().is3xxRedirection());

		Post saved = latestPost();
		assertThat(saved.getPhotoTakenAt()).isNull();
		assertThat(postLocationRepository.findById(saved.getId())).isEmpty();
	}

	@Test
	void 동의했지만_사진에_위치가_없으면_글만_저장한다() throws Exception {
		mockMvc.perform(multipart("/posts").file(photo)
				.param("nickname", "테스터")
				.param("password", "1234")
				.param("locationConsent", "true"))
				.andExpect(status().is3xxRedirection());

		assertThat(postLocationRepository.findById(latestPost().getId())).isEmpty();
	}

	@Test
	void 글을_삭제하면_위치는_완전히_지운다() throws Exception {
		mockMvc.perform(multipart("/posts").file(photoWithGps)
				.param("nickname", "테스터")
				.param("password", "1234")
				.param("locationConsent", "true"));
		Long postId = latestPost().getId();
		assertThat(postLocationRepository.findById(postId)).isPresent();

		mockMvc.perform(post("/posts/" + postId + "/delete").param("password", "1234"))
				.andExpect(redirectedUrl("/"));

		// 게시글은 상태만 바뀌어 남아 있지만, 위치 행은 아예 없어야 한다.
		assertThat(postRepository.findById(postId)).isPresent();
		assertThat(postLocationRepository.findById(postId)).isEmpty();
	}

	// 가장 최근에 저장된 글(번호가 가장 큰 글)
	private Post latestPost() {
		return postRepository.findAll(Sort.by(Sort.Direction.DESC, "id")).get(0);
	}

	@Test
	void 글을_올리면_저장되고_그_글의_상세_화면으로_이동한다() throws Exception {
		// multipart(): 파일이 포함된 form 전송을 흉내 낸다. param(): 글자 칸의 값
		MvcResult result = mockMvc.perform(multipart("/posts").file(photo)
				.param("nickname", "테스터")
				.param("password", "1234")
				.param("memo", "점심"))
				.andExpect(status().is3xxRedirection())
				.andReturn();

		// 가장 최근에 저장된 글(번호가 가장 큰 글)을 꺼내 확인한다.
		Post saved = postRepository.findAll(Sort.by(Sort.Direction.DESC, "id")).get(0);
		assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/posts/" + saved.getId());
		assertThat(saved.getNickname()).isEqualTo("테스터");
		assertThat(saved.getMemo()).isEqualTo("점심");
		assertThat(saved.getStatus()).isEqualTo(PostStatus.ACTIVE);
		// 비밀번호는 원문 그대로가 아니라 해시로 저장되고, 원문과 맞춰 볼 수는 있어야 한다.
		assertThat(saved.getPasswordHash()).isNotEqualTo("1234");
		// 큰 사진과 썸네일 파일이 실제로 저장돼 있어야 한다.
		assertThat(Files.exists(Path.of("build/test-uploads", saved.getImageKey()))).isTrue();
		assertThat(Files.exists(Path.of("build/test-uploads", saved.getThumbnailKey()))).isTrue();
		assertThat(passwordEncoder.matches("1234", saved.getPasswordHash())).isTrue();
	}

	@Test
	void 사진_없이_올리면_글쓰기_화면으로_돌아가고_사진_칸에_메시지가_나온다() throws Exception {
		mockMvc.perform(multipart("/posts")
				.param("nickname", "테스터")
				.param("password", "1234"))
				.andExpect(status().isOk())
				.andExpect(view().name("posts/new"))
				// "form" 객체의 image 칸에 검사 메시지가 있어야 한다.
				.andExpect(model().attributeHasFieldErrors("form", "image"));
	}

	@Test
	void 비밀번호가_4자보다_짧으면_글쓰기_화면으로_돌아간다() throws Exception {
		mockMvc.perform(multipart("/posts").file(photo)
				.param("nickname", "테스터")
				.param("password", "123"))
				.andExpect(view().name("posts/new"))
				.andExpect(model().attributeHasFieldErrors("form", "password"));
	}

	@Test
	void 상세_화면은_글을_보여준다() throws Exception {
		Post post = savePost("1234");

		mockMvc.perform(get("/posts/" + post.getId()))
				.andExpect(status().isOk())
				.andExpect(view().name("posts/detail"))
				.andExpect(model().attributeExists("post"))
				// AI 분석을 넣기 전에 올린 글에는 분석 기록이 없다. 그래도 화면은 열려야 한다.
				.andExpect(model().attributeDoesNotExist("analysis"));
	}

	@Test
	void 글을_올리면_분석_대기_기록이_함께_저장된다() throws Exception {
		mockMvc.perform(multipart("/posts").file(photo)
				.param("nickname", "테스터")
				.param("password", "1234"))
				.andExpect(status().is3xxRedirection());

		// @Transactional 테스트라 글이 확정되지 않아서, 뒤에서 도는 분석은 이 글을 보지 못하고 PENDING 그대로다.
		FoodAnalysis analysis = foodAnalysisRepository.findById(latestPost().getId()).orElseThrow();
		assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.PENDING);
		assertThat(analysis.getAttemptCount()).isZero();
	}

	@Test
	void 목록은_글마다_분석_상태와_칼로리를_함께_넘긴다() throws Exception {
		Post done = savePost("1234");
		FoodAnalysis doneAnalysis = new FoodAnalysis(done);
		doneAnalysis.markDone(new FoodAnalyzerResponse(
				new FoodAnalysisResult(true, List.of(new FoodItem("공기밥", 210, 310, 68, 5, 1)), "HIGH", "1공기"),
				"test-model", 1300, 100), "[]", 2000);
		foodAnalysisRepository.save(doneAnalysis);

		Post pending = savePost("1234");
		foodAnalysisRepository.save(new FoodAnalysis(pending));

		// 분석 기록이 없는 옛날 글
		Post old = savePost("1234");

		MvcResult result = mockMvc.perform(get("/")).andExpect(status().isOk()).andReturn();
		FeedPage feed = (FeedPage) result.getModelAndView().getModel().get("feed");

		PostSummary doneSummary = summaryOf(feed, done);
		assertThat(doneSummary.analysisStatus()).isEqualTo(AnalysisStatus.DONE);
		assertThat(doneSummary.totalKcal()).isEqualByComparingTo("310");
		assertThat(summaryOf(feed, pending).analysisStatus()).isEqualTo(AnalysisStatus.PENDING);
		assertThat(summaryOf(feed, pending).totalKcal()).isNull();
		assertThat(summaryOf(feed, old).analysisStatus()).isNull();
	}

	@Test
	void 목록은_24개씩_쪽_번호로_넘기고_범위_밖_번호는_끝_쪽으로_맞춘다() throws Exception {
		// 개발 DB에 글이 이미 있어도 되도록, 지금 ACTIVE인 전체 글 수로 기대값을 계산한다
		for (int i = 0; i < 25; i++) {
			savePost("1234");
		}
		long active = postRepository.findAll().stream().filter(post -> post.getStatus() == PostStatus.ACTIVE).count();
		int lastPage = (int) Math.ceil(active / 24.0);

		// 첫 쪽: 24개, 가장 최신 글이 맨 앞, 이전은 없고 다음은 있다
		FeedPage first = feedOf(get("/"));
		assertThat(first.posts()).hasSize(24);
		assertThat(first.posts().get(0).id()).isEqualTo(latestPost().getId());
		assertThat(first.nav().page()).isEqualTo(1);
		assertThat(first.nav().totalPages()).isEqualTo(lastPage);
		assertThat(first.nav().hasPrev()).isFalse();
		assertThat(first.nav().hasNext()).isTrue();

		// 마지막 쪽: 나머지 글만, 다음은 없다
		FeedPage last = feedOf(get("/").param("page", String.valueOf(lastPage)));
		assertThat(last.posts()).hasSize((int) (active - 24L * (lastPage - 1)));
		assertThat(last.nav().hasNext()).isFalse();

		// 범위 밖: 너무 큰 번호는 마지막 쪽으로, 0은 첫 쪽으로
		assertThat(feedOf(get("/").param("page", "999")).nav().page()).isEqualTo(lastPage);
		assertThat(feedOf(get("/").param("page", "0")).nav().page()).isEqualTo(1);
	}

	private FeedPage feedOf(MockHttpServletRequestBuilder request) throws Exception {
		MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
		return (FeedPage) result.getModelAndView().getModel().get("feed");
	}

	private PostSummary summaryOf(FeedPage feed, Post post) {
		return feed.posts().stream().filter(summary -> summary.id().equals(post.getId())).findFirst().orElseThrow();
	}

	@Test
	void 상세_화면은_분석_기록이_있으면_함께_넘긴다() throws Exception {
		Post post = savePost("1234");
		foodAnalysisRepository.save(new FoodAnalysis(post));

		mockMvc.perform(get("/posts/" + post.getId()))
				.andExpect(status().isOk())
				.andExpect(model().attributeExists("analysis"));
	}

	@Test
	void 없는_글의_상세는_404_에러_화면을_보여준다() throws Exception {
		mockMvc.perform(get("/posts/999999999"))
				.andExpect(status().isNotFound())
				.andExpect(view().name("common/error"));
	}

	@Test
	void 비밀번호가_맞으면_상태만_바꿔_삭제하고_그_글은_더_볼_수_없다() throws Exception {
		Post post = savePost("1234");

		mockMvc.perform(post("/posts/" + post.getId() + "/delete").param("password", "1234"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/"));

		// DB에서 지워지지 않고, 상태와 삭제 시각만 바뀌어 있어야 한다.
		Post deleted = postRepository.findById(post.getId()).orElseThrow();
		assertThat(deleted.getStatus()).isEqualTo(PostStatus.DELETED_BY_AUTHOR);
		assertThat(deleted.getDeletedAt()).isNotNull();

		// 삭제된 글의 상세는 404
		mockMvc.perform(get("/posts/" + post.getId()))
				.andExpect(status().isNotFound());
	}

	@Test
	void 비밀번호가_틀리면_삭제하지_않고_상세_화면으로_돌아가_메시지를_보여준다() throws Exception {
		Post post = savePost("1234");

		mockMvc.perform(post("/posts/" + post.getId() + "/delete").param("password", "0000"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/posts/" + post.getId()))
				// flash: redirect한 다음 화면에 한 번만 넘어가는 값
				.andExpect(flash().attribute("deleteError", ErrorCode.INVALID_PASSWORD.getMessage()));

		assertThat(postRepository.findById(post.getId()).orElseThrow().getStatus()).isEqualTo(PostStatus.ACTIVE);
	}

	@Test
	void 사람이_상세_화면을_열면_조회수가_1_오르고_그_숫자가_화면에_나간다() throws Exception {
		Post post = savePost("1234");

		MvcResult result = mockMvc.perform(get("/posts/" + post.getId()).header(HttpHeaders.USER_AGENT, BROWSER))
				.andExpect(status().isOk())
				.andReturn();

		// 먼저 올리고 읽으므로, 화면에 나가는 숫자에 이번 방문이 들어 있다
		PostSummary shown = (PostSummary) result.getModelAndView().getModel().get("post");
		assertThat(shown.viewCount()).isEqualTo(1);
		assertThat(viewCountOf(post)).isEqualTo(1);
	}

	@Test
	void 봇이_열거나_User_Agent가_없으면_조회수를_세지_않는다() throws Exception {
		Post post = savePost("1234");

		mockMvc.perform(get("/posts/" + post.getId())
				.header(HttpHeaders.USER_AGENT, "Mozilla/5.0 (compatible; Googlebot/2.1)"))
				.andExpect(status().isOk());
		mockMvc.perform(get("/posts/" + post.getId())).andExpect(status().isOk());

		assertThat(viewCountOf(post)).isZero();
	}

	@Test
	void 같은_글_화면이나_작성_화면에서_넘어온_것은_조회수를_세지_않는다() throws Exception {
		Post post = savePost("1234");
		String url = "/posts/" + post.getId();

		// 댓글을 쓰거나 지운 뒤 서버가 이 화면을 새로 열어 줄 때: 앞 화면(Referer)이 같은 글이다
		mockMvc.perform(get(url).header(HttpHeaders.USER_AGENT, BROWSER)
				.header(HttpHeaders.REFERER, "https://jjikmuk.com" + url + "?commentCursor=5"))
				.andExpect(status().isOk());
		// 글을 올린 사람의 첫 화면: 앞 화면이 작성 화면이다
		mockMvc.perform(get(url).header(HttpHeaders.USER_AGENT, BROWSER)
				.header(HttpHeaders.REFERER, "https://jjikmuk.com/posts/new"))
				.andExpect(status().isOk());
		assertThat(viewCountOf(post)).isZero();

		// 목록에서 들어온 것은 센다
		mockMvc.perform(get(url).header(HttpHeaders.USER_AGENT, BROWSER)
				.header(HttpHeaders.REFERER, "https://jjikmuk.com/?page=2"))
				.andExpect(status().isOk());
		assertThat(viewCountOf(post)).isEqualTo(1);
	}

	@Test
	void 목록은_글마다_좋아요_수와_조회수와_짧은_날짜를_함께_넘긴다() throws Exception {
		Post post = savePost("1234");
		postRepository.incrementLikeCount(post.getId(), PostStatus.ACTIVE);
		postRepository.incrementLikeCount(post.getId(), PostStatus.ACTIVE);
		postRepository.incrementViewCount(post.getId(), PostStatus.ACTIVE);

		MvcResult result = mockMvc.perform(get("/")).andExpect(status().isOk()).andReturn();
		FeedPage feed = (FeedPage) result.getModelAndView().getModel().get("feed");

		PostSummary summary = summaryOf(feed, post);
		assertThat(summary.likeCount()).isEqualTo(2);
		assertThat(summary.viewCount()).isEqualTo(1);
		// 카드의 날짜: PC용은 연도까지(2026.11.11), 휴대폰용은 짧게(11.11)
		assertThat(summary.createdDate()).matches("\\d{4}\\.\\d{2}\\.\\d{2}");
		assertThat(summary.createdShort()).matches("\\d{2}\\.\\d{2}");
	}

	// DB에 저장된 조회수를 다시 읽는다 (UPDATE로 올린 값이라 메모리의 post 객체에는 반영되지 않는다)
	private int viewCountOf(Post post) {
		return postRepository.findById(post.getId()).orElseThrow().getViewCount();
	}

	// ---- 메모 수정 (JM-53, 지인 피드백 7번) ----
	// 따로 수정 화면이 없다. 상세 화면의 "메모 수정" 칸에서 보낸 값을 받아 처리하고, 어느 쪽이든 그 글의 상세로 돌아간다.

	@Test
	void 비밀번호가_맞으면_메모만_바뀌고_그_글의_상세로_돌아간다() throws Exception {
		Post post = savePost("1234", "점심");
		Instant createdAt = post.getCreatedAt();

		mockMvc.perform(post("/posts/" + post.getId() + "/memo").param("memo", "  저녁으로 고침  ").param("password", "1234"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/posts/" + post.getId()))
				// 저장됐으면 보여 줄 메시지가 없다
				.andExpect(flash().attributeCount(0));

		Post edited = postRepository.findById(post.getId()).orElseThrow();
		// 앞뒤 공백은 떼고 저장한다 (글을 올릴 때와 같음)
		assertThat(edited.getMemo()).isEqualTo("저녁으로 고침");
		// 메모 말고는 그대로
		assertThat(edited.getNickname()).isEqualTo("테스터");
		assertThat(edited.getImageKey()).isEqualTo("test.jpg");
		assertThat(edited.getThumbnailKey()).isEqualTo("test-thumb.jpg");
		assertThat(edited.getStatus()).isEqualTo(PostStatus.ACTIVE);
		assertThat(edited.getCreatedAt()).isEqualTo(createdAt);
		assertThat(passwordEncoder.matches("1234", edited.getPasswordHash())).isTrue();
	}

	@Test
	void 메모를_비우고_저장하면_메모가_없어진다() throws Exception {
		Post post = savePost("1234", "점심");

		mockMvc.perform(post("/posts/" + post.getId() + "/memo").param("memo", "   ").param("password", "1234"))
				.andExpect(redirectedUrl("/posts/" + post.getId()));

		assertThat(postRepository.findById(post.getId()).orElseThrow().getMemo()).isNull();
	}

	@Test
	void 메모가_없던_글에_메모를_새로_쓸_수_있다() throws Exception {
		Post post = savePost("1234");

		mockMvc.perform(post("/posts/" + post.getId() + "/memo").param("memo", "나중에 쓴 메모").param("password", "1234"))
				.andExpect(redirectedUrl("/posts/" + post.getId()));

		assertThat(postRepository.findById(post.getId()).orElseThrow().getMemo()).isEqualTo("나중에 쓴 메모");
	}

	@Test
	void 비밀번호가_틀리면_메모를_바꾸지_않고_상세로_돌아가_메시지와_쓰던_메모를_보여준다() throws Exception {
		Post post = savePost("1234", "점심");

		mockMvc.perform(post("/posts/" + post.getId() + "/memo").param("memo", "남이 고친 메모").param("password", "0000"))
				.andExpect(status().is3xxRedirection())
				// 상세 화면의 메모 수정 칸 자리로 돌아간다
				.andExpect(redirectedUrl("/posts/" + post.getId() + "#memo-edit"))
				// flash: redirect한 다음 화면에 한 번만 넘어가는 값. 메시지와, 칸에 다시 채울 고쳐 쓰던 메모
				.andExpect(flash().attribute("memoError", ErrorCode.INVALID_PASSWORD.getMessage()))
				.andExpect(flash().attribute("memoDraft", "남이 고친 메모"));

		assertThat(postRepository.findById(post.getId()).orElseThrow().getMemo()).isEqualTo("점심");
	}

	@Test
	void 비밀번호를_비우거나_메모가_300자를_넘으면_저장하지_않고_메시지를_보여준다() throws Exception {
		Post post = savePost("1234", "점심");

		mockMvc.perform(post("/posts/" + post.getId() + "/memo").param("memo", "고친 메모").param("password", ""))
				.andExpect(redirectedUrl("/posts/" + post.getId() + "#memo-edit"))
				.andExpect(flash().attribute("memoError", "비밀번호를 입력해 주세요."));

		// 비밀번호가 맞아도 301자는 저장하지 않는다
		mockMvc.perform(post("/posts/" + post.getId() + "/memo").param("memo", "가".repeat(301)).param("password", "1234"))
				.andExpect(redirectedUrl("/posts/" + post.getId() + "#memo-edit"))
				.andExpect(flash().attribute("memoError", "메모는 300자까지 쓸 수 있어요."));

		assertThat(postRepository.findById(post.getId()).orElseThrow().getMemo()).isEqualTo("점심");

		// 300자까지는 저장된다
		mockMvc.perform(post("/posts/" + post.getId() + "/memo").param("memo", "가".repeat(300)).param("password", "1234"))
				.andExpect(redirectedUrl("/posts/" + post.getId()));
		assertThat(postRepository.findById(post.getId()).orElseThrow().getMemo()).hasSize(300);
	}

	@Test
	void 없는_글이나_지운_글은_메모를_고칠_수_없다() throws Exception {
		Post deleted = savePost("1234", "점심");
		mockMvc.perform(post("/posts/" + deleted.getId() + "/delete").param("password", "1234"))
				.andExpect(redirectedUrl("/"));

		// 지운 글: 비밀번호가 맞아도 404이고 메모는 그대로다
		mockMvc.perform(post("/posts/" + deleted.getId() + "/memo").param("memo", "지운 글 고치기").param("password", "1234"))
				.andExpect(status().isNotFound());
		assertThat(postRepository.findById(deleted.getId()).orElseThrow().getMemo()).isEqualTo("점심");

		// 없는 글
		mockMvc.perform(post("/posts/999999999/memo").param("memo", "메모").param("password", "1234"))
				.andExpect(status().isNotFound());
	}

	// 테스트용 글을 DB에 바로 저장한다. (사진 파일은 만들지 않고 이름만 넣는다)
	private Post savePost(String password) {
		return savePost(password, null);
	}

	private Post savePost(String password, String memo) {
		return postRepository.save(new Post("테스터", passwordEncoder.encode(password), memo, "test.jpg", "test-thumb.jpg", null));
	}

	@Test
	void 사진이_아닌_파일은_글쓰기_화면의_사진_칸에_메시지를_보여준다() throws Exception {
		// 이름과 형식은 JPEG처럼 꾸몄지만 내용은 사진이 아닌 파일 → 파일 내용 검사(매직 바이트)에서 걸린다.
		MockMultipartFile fake = new MockMultipartFile("image", "food.jpg", "image/jpeg", "not an image".getBytes());

		mockMvc.perform(multipart("/posts").file(fake)
				.param("nickname", "테스터")
				.param("password", "1234"))
				.andExpect(status().isOk())
				.andExpect(view().name("posts/new"))
				.andExpect(model().attributeHasFieldErrorCode("form", "image", ErrorCode.INVALID_IMAGE.name()));
	}
}
