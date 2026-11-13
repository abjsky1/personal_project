package backend.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import backend.admin.AdminTokenInterceptor;
import backend.comment.model.entity.Comment;
import backend.comment.model.repository.CommentRepository;
import backend.feedback.model.entity.Feedback;
import backend.feedback.model.entity.FeedbackProgress;
import backend.feedback.model.entity.FeedbackReply;
import backend.feedback.model.repository.FeedbackReplyRepository;
import backend.feedback.model.repository.FeedbackRepository;
import backend.location.model.entity.PostLocation;
import backend.location.model.repository.PostLocationRepository;
import backend.location.model.vo.GeoPoint;
import backend.post.model.entity.Post;
import backend.post.model.entity.PostStatus;
import backend.post.model.repository.PostRepository;

// 운영자 API의 토큰 검사와 삭제, 피드백 처리를 확인하는 테스트
// @TestPropertySource: 이 테스트에서만 운영자 토큰을 정해 둔다 (기본값은 비어 있어서 운영자 기능이 꺼져 있다 → AdminApiDisabledTest)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.admin.token=test-admin-token")
class AdminApiControllerTest {

	private static final String TOKEN = "test-admin-token";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private PostLocationRepository postLocationRepository;

	@Autowired
	private CommentRepository commentRepository;

	@Autowired
	private FeedbackRepository feedbackRepository;

	@Autowired
	private FeedbackReplyRepository feedbackReplyRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	void 토큰이_없으면_401이고_아무것도_바뀌지_않는다() throws Exception {
		Post post = savePost();

		mockMvc.perform(post("/api/admin/posts/" + post.getId() + "/delete"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("ADMIN_UNAUTHORIZED"));

		assertThat(postRepository.findById(post.getId()).orElseThrow().getStatus()).isEqualTo(PostStatus.ACTIVE);
	}

	@Test
	void 토큰이_틀리면_401이다() throws Exception {
		Post post = savePost();

		mockMvc.perform(post("/api/admin/posts/" + post.getId() + "/delete").header(AdminTokenInterceptor.HEADER, "wrong"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("ADMIN_UNAUTHORIZED"));
	}

	@Test
	void 글을_운영자_삭제하면_상태가_바뀌고_위치는_지워지고_상세는_404다() throws Exception {
		Post post = savePost();
		postLocationRepository.save(new PostLocation(post, GeoPoint.of(37.566535, 126.977969), Instant.now()));

		mockMvc.perform(post("/api/admin/posts/" + post.getId() + "/delete").header(AdminTokenInterceptor.HEADER, TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(post.getId()))
				.andExpect(jsonPath("$.status").value("DELETED_BY_ADMIN"));

		Post deleted = postRepository.findById(post.getId()).orElseThrow();
		assertThat(deleted.getStatus()).isEqualTo(PostStatus.DELETED_BY_ADMIN);
		assertThat(deleted.getDeletedAt()).isNotNull();
		assertThat(postLocationRepository.findById(post.getId())).isEmpty();

		mockMvc.perform(get("/posts/" + post.getId())).andExpect(status().isNotFound());
	}

	@Test
	void 댓글을_운영자_삭제하면_글의_댓글_수에서_빠진다() throws Exception {
		Post post = savePost();
		Comment comment = commentRepository.save(new Comment(post.getId(), "댓글러", passwordEncoder.encode("1234"), "지워질 댓글"));

		mockMvc.perform(post("/api/admin/comments/" + comment.getId() + "/delete").header(AdminTokenInterceptor.HEADER, TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("DELETED_BY_ADMIN"));

		assertThat(commentRepository.findById(comment.getId()).orElseThrow().getStatus()).isEqualTo(PostStatus.DELETED_BY_ADMIN);
		assertThat(commentRepository.countByPostIdAndStatus(post.getId(), PostStatus.ACTIVE)).isZero();
	}

	@Test
	void 피드백에_처리_상태와_답변을_넣으면_상세에_보인다() throws Exception {
		Feedback feedback = feedbackRepository.save(new Feedback("사용자", passwordEncoder.encode("1234"), "사진이 안 올라가요", "내용"));

		mockMvc.perform(patch("/api/admin/feedback/" + feedback.getId())
				.header(AdminTokenInterceptor.HEADER, TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"progress\":\"DONE\",\"reply\":\"  수정했습니다. 피드백 감사합니다 :)  \"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.progress").value("DONE"))
				.andExpect(jsonPath("$.replies.length()").value(1))
				.andExpect(jsonPath("$.replies[0].id").isNumber())
				.andExpect(jsonPath("$.replies[0].content").value("수정했습니다. 피드백 감사합니다 :)"));

		Feedback updated = feedbackRepository.findById(feedback.getId()).orElseThrow();
		assertThat(updated.getProgress()).isEqualTo(FeedbackProgress.DONE);
		List<FeedbackReply> replies = repliesOf(feedback);
		assertThat(replies).hasSize(1);
		assertThat(replies.get(0).getContent()).isEqualTo("수정했습니다. 피드백 감사합니다 :)");
		assertThat(replies.get(0).getCreatedAt()).isNotNull();
	}

	// JM-77: 전에는 답변을 다시 달면 앞의 답변이 덮어써져 사라졌다
	@Test
	void 답변을_다시_달면_앞의_답변을_덮어쓰지_않고_아래에_이어서_달린다() throws Exception {
		Feedback feedback = feedbackRepository.save(new Feedback("사용자", passwordEncoder.encode("1234"), "제목", "내용"));

		mockMvc.perform(patch("/api/admin/feedback/" + feedback.getId())
				.header(AdminTokenInterceptor.HEADER, TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reply\":\"업데이트 목록에 추가했습니다. 피드백 감사합니다 :)\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.replies.length()").value(1));

		mockMvc.perform(patch("/api/admin/feedback/" + feedback.getId())
				.header(AdminTokenInterceptor.HEADER, TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"progress\":\"DONE\",\"reply\":\"반영했습니다.\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.progress").value("DONE"))
				.andExpect(jsonPath("$.replies.length()").value(2))
				.andExpect(jsonPath("$.replies[0].content").value("업데이트 목록에 추가했습니다. 피드백 감사합니다 :)"))
				.andExpect(jsonPath("$.replies[1].content").value("반영했습니다."));

		assertThat(repliesOf(feedback)).extracting(FeedbackReply::getContent)
				.containsExactly("업데이트 목록에 추가했습니다. 피드백 감사합니다 :)", "반영했습니다.");
	}

	@Test
	void 처리_상태만_바꾸면_답변은_그대로다() throws Exception {
		Feedback feedback = feedbackRepository.save(new Feedback("사용자", passwordEncoder.encode("1234"), "제목", "내용"));
		feedbackReplyRepository.save(new FeedbackReply(feedback.getId(), "먼저 단 답변"));

		mockMvc.perform(patch("/api/admin/feedback/" + feedback.getId())
				.header(AdminTokenInterceptor.HEADER, TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"progress\":\"ON_HOLD\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.progress").value("ON_HOLD"))
				.andExpect(jsonPath("$.replies.length()").value(1))
				.andExpect(jsonPath("$.replies[0].content").value("먼저 단 답변"));
	}

	@Test
	void 잘못_단_답변을_지우면_그_답변만_빠지고_다시_지우면_404다() throws Exception {
		Feedback feedback = feedbackRepository.save(new Feedback("사용자", passwordEncoder.encode("1234"), "제목", "내용"));
		FeedbackReply wrong = feedbackReplyRepository.save(new FeedbackReply(feedback.getId(), "다른 글에 달 답변을 잘못 달았다"));
		FeedbackReply right = feedbackReplyRepository.save(new FeedbackReply(feedback.getId(), "남을 답변"));
		String deleteUrl = "/api/admin/feedback/replies/" + wrong.getId() + "/delete";

		// 토큰이 없으면 지워지지 않는다
		mockMvc.perform(post(deleteUrl)).andExpect(status().isUnauthorized());
		assertThat(repliesOf(feedback)).hasSize(2);

		mockMvc.perform(post(deleteUrl).header(AdminTokenInterceptor.HEADER, TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(wrong.getId()))
				.andExpect(jsonPath("$.status").value("DELETED_BY_ADMIN"));

		// 상태만 바뀌어 DB에는 남고, 화면에 보이는 답변에서는 빠진다
		FeedbackReply deleted = feedbackReplyRepository.findById(wrong.getId()).orElseThrow();
		assertThat(deleted.getStatus()).isEqualTo(PostStatus.DELETED_BY_ADMIN);
		assertThat(deleted.getDeletedAt()).isNotNull();
		assertThat(repliesOf(feedback)).extracting(FeedbackReply::getId).containsExactly(right.getId());

		// 같은 요청을 한 번 더 보내도 다른 답변은 지워지지 않는다 (이미 지운 답변은 404)
		mockMvc.perform(post(deleteUrl).header(AdminTokenInterceptor.HEADER, TOKEN))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("FEEDBACK_REPLY_NOT_FOUND"));
		assertThat(repliesOf(feedback)).hasSize(1);
	}

	@Test
	void 없는_답변을_지우면_404다() throws Exception {
		mockMvc.perform(post("/api/admin/feedback/replies/999999999/delete").header(AdminTokenInterceptor.HEADER, TOKEN))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("FEEDBACK_REPLY_NOT_FOUND"));
	}

	@Test
	void 바꿀_내용이_없거나_없는_상태_값이면_400이다() throws Exception {
		Feedback feedback = feedbackRepository.save(new Feedback("사용자", passwordEncoder.encode("1234"), "제목", "내용"));

		// 둘 다 비어 있음
		mockMvc.perform(patch("/api/admin/feedback/" + feedback.getId())
				.header(AdminTokenInterceptor.HEADER, TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

		// 없는 처리 상태
		mockMvc.perform(patch("/api/admin/feedback/" + feedback.getId())
				.header(AdminTokenInterceptor.HEADER, TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"progress\":\"FINISHED\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void 없는_피드백을_지우면_404다() throws Exception {
		mockMvc.perform(post("/api/admin/feedback/999999999/delete").header(AdminTokenInterceptor.HEADER, TOKEN))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("FEEDBACK_NOT_FOUND"));
	}

	// 화면에 보이는(지워지지 않은) 답변을 달린 순서대로
	private List<FeedbackReply> repliesOf(Feedback feedback) {
		return feedbackReplyRepository.findByFeedbackIdAndStatusOrderByIdAsc(feedback.getId(), PostStatus.ACTIVE);
	}

	private Post savePost() {
		return postRepository.save(new Post("테스터", passwordEncoder.encode("1234"), null, "test.jpg", "test-thumb.jpg", null));
	}
}
