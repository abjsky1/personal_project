package backend.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

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
import backend.feedback.model.repository.FeedbackRepository;
import backend.location.model.entity.PostLocation;
import backend.location.model.repository.PostLocationRepository;
import backend.location.model.vo.GeoPoint;
import backend.post.model.entity.Post;
import backend.post.model.entity.PostStatus;
import backend.post.model.repository.PostRepository;

// 운영자 API의 토큰 검사와 삭제·피드백 처리를 확인하는 테스트
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
				.content("{\"progress\":\"DONE\",\"reply\":\"  고쳤어요. 고마워요!  \"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.progress").value("DONE"))
				.andExpect(jsonPath("$.adminReply").value("고쳤어요. 고마워요!"));

		Feedback updated = feedbackRepository.findById(feedback.getId()).orElseThrow();
		assertThat(updated.getProgress()).isEqualTo(FeedbackProgress.DONE);
		assertThat(updated.getAdminReply()).isEqualTo("고쳤어요. 고마워요!");
		assertThat(updated.getRepliedAt()).isNotNull();
	}

	@Test
	void 처리_상태만_바꾸면_답변은_그대로다() throws Exception {
		Feedback feedback = feedbackRepository.save(new Feedback("사용자", passwordEncoder.encode("1234"), "제목", "내용"));
		feedback.reply("먼저 단 답변");
		feedbackRepository.save(feedback);

		mockMvc.perform(patch("/api/admin/feedback/" + feedback.getId())
				.header(AdminTokenInterceptor.HEADER, TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"progress\":\"ON_HOLD\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.progress").value("ON_HOLD"))
				.andExpect(jsonPath("$.adminReply").value("먼저 단 답변"));
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

	private Post savePost() {
		return postRepository.save(new Post("테스터", passwordEncoder.encode("1234"), null, "test.jpg", "test-thumb.jpg", null));
	}
}
