package backend.feedback.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import backend.common.error.ErrorCode;
import backend.feedback.model.dto.FeedbackDetail;
import backend.feedback.model.dto.FeedbackPage;
import backend.feedback.model.dto.FeedbackReplyView;
import backend.feedback.model.dto.FeedbackSummary;
import backend.feedback.model.entity.Feedback;
import backend.feedback.model.entity.FeedbackProgress;
import backend.feedback.model.entity.FeedbackReply;
import backend.feedback.model.repository.FeedbackReplyRepository;
import backend.feedback.model.repository.FeedbackRepository;
import backend.post.model.entity.PostStatus;

// 피드백 게시판의 작성, 목록, 상세, 삭제 요청을 실제로 보내 보는 테스트 (PostControllerTest와 같은 방식)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FeedbackControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private FeedbackRepository feedbackRepository;

	@Autowired
	private FeedbackReplyRepository feedbackReplyRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	void 피드백을_남기면_저장되고_상세_화면으로_이동한다() throws Exception {
		MvcResult result = mockMvc.perform(post("/feedback")
				.param("title", "  사진이 안 올라가요  ")
				.param("content", "아이폰에서 올리면 멈춰요")
				.param("nickname", "사용자")
				.param("password", "1234"))
				.andExpect(status().is3xxRedirection())
				.andReturn();

		Feedback saved = latest();
		assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/feedback/" + saved.getId());
		assertThat(saved.getTitle()).isEqualTo("사진이 안 올라가요");
		assertThat(saved.getContent()).isEqualTo("아이폰에서 올리면 멈춰요");
		assertThat(saved.getNickname()).isEqualTo("사용자");
		assertThat(saved.getStatus()).isEqualTo(PostStatus.ACTIVE);
		// 처음 상태는 "접수", 답변은 아직 없다
		assertThat(saved.getProgress()).isEqualTo(FeedbackProgress.RECEIVED);
		assertThat(feedbackReplyRepository.findByFeedbackIdAndStatusOrderByIdAsc(saved.getId(), PostStatus.ACTIVE)).isEmpty();
		assertThat(passwordEncoder.matches("1234", saved.getPasswordHash())).isTrue();
	}

	@Test
	void 제목이_비면_저장하지_않고_작성_화면에_메시지를_보여준다() throws Exception {
		// 개발 DB에 이미 글이 있을 수 있으므로 "0개"가 아니라 "늘지 않았다"로 확인한다
		long before = feedbackRepository.count();

		mockMvc.perform(post("/feedback")
				.param("title", " ")
				.param("content", "내용")
				.param("nickname", "사용자")
				.param("password", "1234"))
				.andExpect(status().isOk())
				.andExpect(view().name("feedback/new"))
				.andExpect(model().attributeHasFieldErrors("form", "title"));

		assertThat(feedbackRepository.count()).isEqualTo(before);
	}

	@Test
	void 비밀번호가_짧으면_작성_화면으로_돌아간다() throws Exception {
		mockMvc.perform(post("/feedback")
				.param("title", "제목")
				.param("content", "내용")
				.param("nickname", "사용자")
				.param("password", "123"))
				.andExpect(view().name("feedback/new"))
				.andExpect(model().attributeHasFieldErrors("form", "password"));
	}

	@Test
	void 목록은_최신순이고_삭제된_글은_번호와_자리만_남는다() throws Exception {
		Feedback older = save("먼저 쓴 글");
		Feedback byAuthor = save("작성자가 지운 글");
		Feedback byAdmin = save("운영자가 지운 글");
		Feedback newer = save("나중에 쓴 글");
		byAuthor.deleteByAuthor();
		byAdmin.deleteByAdmin();
		feedbackRepository.save(byAuthor);
		feedbackRepository.save(byAdmin);

		FeedbackPage page = pageOf(get("/feedback"));

		// 개발 DB에 다른 글이 있어도 되도록, 방금 저장한 넷이 번호가 큰 순서로 맨 앞에 오는지만 본다.
		// 지운 글도 줄이 빠지지 않는다 (JM-76): 번호가 당겨지지 않게 번호와 자리를 남긴다
		assertThat(page.feedbacks()).extracting(FeedbackSummary::id)
				.startsWith(newer.getId(), byAdmin.getId(), byAuthor.getId(), older.getId());
		assertThat(page.feedbacks().subList(0, 4)).extracting(FeedbackSummary::deleted).containsExactly(false, true, true, false);
		// 지운 글의 내용은 화면으로 넘기지 않는다 (누가 지웠든 같다)
		for (FeedbackSummary gone : page.feedbacks().subList(1, 3)) {
			assertThat(gone.title()).isNull();
			assertThat(gone.nickname()).isNull();
			assertThat(gone.createdAt()).isNull();
			assertThat(gone.progress()).isNull();
			assertThat(gone.hasReply()).isFalse();
		}
		assertThat(page.feedbacks().get(0).title()).isEqualTo("나중에 쓴 글");
		assertThat(page.feedbacks().get(0).progress()).isEqualTo(FeedbackProgress.RECEIVED);
	}

	@Test
	void 글이_21개면_20개씩_쪽으로_나뉘고_둘째_쪽은_21번째_글부터다() throws Exception {
		for (int i = 1; i <= 21; i++) {
			save("글 " + i);
		}

		// 방금 저장한 21개가 가장 최신이라 첫 쪽은 그중 20개(글 21 … 글 2)이고, 둘째 쪽은 글 1부터 시작한다
		// (개발 DB에 예전 글이 더 있어도 되도록 둘째 쪽의 개수는 확인하지 않는다)
		FeedbackPage first = pageOf(get("/feedback"));
		assertThat(first.feedbacks()).hasSize(20);
		assertThat(first.feedbacks().get(0).title()).isEqualTo("글 21");
		assertThat(first.feedbacks().get(19).title()).isEqualTo("글 2");
		assertThat(first.nav().page()).isEqualTo(1);
		assertThat(first.nav().hasPrev()).isFalse();
		assertThat(first.nav().hasNext()).isTrue();

		FeedbackPage second = pageOf(get("/feedback").param("page", "2"));
		assertThat(second.feedbacks().get(0).title()).isEqualTo("글 1");
		assertThat(second.nav().page()).isEqualTo(2);
		assertThat(second.nav().hasPrev()).isTrue();
	}

	@Test
	void 상세_화면은_내용과_처리_상태와_운영자_답변을_달린_순서대로_보여준다() throws Exception {
		Feedback feedback = save("답변 받은 글");
		feedback.changeProgress(FeedbackProgress.DONE);
		feedbackRepository.save(feedback);
		// 답변 셋 중 가운데 것은 운영자가 지운 답변이라 화면에 나오지 않는다
		feedbackReplyRepository.save(new FeedbackReply(feedback.getId(), "업데이트 목록에 추가했습니다. 피드백 감사합니다 :)"));
		saveDeletedReply(feedback);
		feedbackReplyRepository.save(new FeedbackReply(feedback.getId(), "반영했습니다."));

		FeedbackDetail detail = detailOf(feedback);

		assertThat(detail.title()).isEqualTo("답변 받은 글");
		assertThat(detail.progress()).isEqualTo(FeedbackProgress.DONE);
		// 먼저 단 답변이 위, 나중에 단 답변이 아래 (JM-77: 다시 달아도 덮어쓰지 않고 이어서 달린다)
		assertThat(detail.replies()).extracting(FeedbackReplyView::content)
				.containsExactly("업데이트 목록에 추가했습니다. 피드백 감사합니다 :)", "반영했습니다.");
		assertThat(detail.replies()).allSatisfy(reply -> assertThat(reply.createdAt()).isNotBlank());
		// 목록에도 "답변 있음"이 표시된다
		FeedbackPage page = pageOf(get("/feedback"));
		assertThat(page.feedbacks().get(0).hasReply()).isTrue();
	}

	@Test
	void 답변이_없거나_모두_지워진_글은_목록에_답변_있음이_없다() throws Exception {
		Feedback onlyDeleted = save("답변이 지워진 글");
		saveDeletedReply(onlyDeleted);
		Feedback never = save("답변이 없는 글");

		FeedbackPage page = pageOf(get("/feedback"));
		assertThat(page.feedbacks().subList(0, 2)).extracting(FeedbackSummary::id)
				.containsExactly(never.getId(), onlyDeleted.getId());
		assertThat(page.feedbacks().subList(0, 2)).extracting(FeedbackSummary::hasReply).containsExactly(false, false);
		assertThat(detailOf(onlyDeleted).replies()).isEmpty();
		assertThat(detailOf(never).replies()).isEmpty();
	}

	@Test
	void 없는_피드백의_상세는_404_에러_화면을_보여준다() throws Exception {
		mockMvc.perform(get("/feedback/999999999"))
				.andExpect(status().isNotFound())
				.andExpect(view().name("common/error"));
	}

	@Test
	void 비밀번호가_맞으면_상태만_바꿔_삭제하고_목록으로_이동한다() throws Exception {
		Feedback feedback = save("지울 글");

		mockMvc.perform(post("/feedback/" + feedback.getId() + "/delete").param("password", "1234"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/feedback"));

		Feedback deleted = feedbackRepository.findById(feedback.getId()).orElseThrow();
		assertThat(deleted.getStatus()).isEqualTo(PostStatus.DELETED_BY_AUTHOR);
		assertThat(deleted.getDeletedAt()).isNotNull();

		mockMvc.perform(get("/feedback/" + feedback.getId()))
				.andExpect(status().isNotFound());
	}

	@Test
	void 비밀번호가_틀리면_삭제하지_않고_상세로_돌아가_메시지를_보여준다() throws Exception {
		Feedback feedback = save("안 지워질 글");

		mockMvc.perform(post("/feedback/" + feedback.getId() + "/delete").param("password", "0000"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/feedback/" + feedback.getId()))
				.andExpect(flash().attribute("deleteError", ErrorCode.INVALID_PASSWORD.getMessage()));

		assertThat(feedbackRepository.findById(feedback.getId()).orElseThrow().getStatus()).isEqualTo(PostStatus.ACTIVE);
	}

	// 목록 요청을 보내고 Model의 FeedbackPage를 꺼낸다
	private FeedbackPage pageOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
		MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
		return (FeedbackPage) result.getModelAndView().getModel().get("page");
	}

	// 상세 요청을 보내고 Model의 FeedbackDetail을 꺼낸다
	private FeedbackDetail detailOf(Feedback feedback) throws Exception {
		MvcResult result = mockMvc.perform(get("/feedback/" + feedback.getId()))
				.andExpect(status().isOk())
				.andExpect(view().name("feedback/detail"))
				.andReturn();
		return (FeedbackDetail) result.getModelAndView().getModel().get("feedback");
	}

	// 운영자가 지운 답변 하나를 만든다 (잘못 달아서 지운 경우)
	private void saveDeletedReply(Feedback feedback) {
		FeedbackReply wrong = new FeedbackReply(feedback.getId(), "잘못 단 답변");
		wrong.deleteByAdmin();
		feedbackReplyRepository.save(wrong);
	}

	// 비밀번호 1234인 테스트용 피드백을 DB에 바로 저장한다
	private Feedback save(String title) {
		return feedbackRepository.save(new Feedback("사용자", passwordEncoder.encode("1234"), title, "내용"));
	}

	private Feedback latest() {
		return feedbackRepository.findAll(Sort.by(Sort.Direction.DESC, "id")).get(0);
	}
}
