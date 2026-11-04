package backend.comment.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import backend.comment.model.dto.CommentPage;
import backend.comment.model.dto.CommentView;
import backend.comment.model.entity.Comment;
import backend.comment.model.repository.CommentRepository;
import backend.common.error.ErrorCode;
import backend.post.model.dto.FeedPage;
import backend.post.model.entity.Post;
import backend.post.model.entity.PostStatus;
import backend.post.model.repository.PostRepository;

// 댓글 달기·보기·지우기 요청을 실제로 보내 보고 저장과 검사가 제대로 되는지 확인하는 테스트 (PostControllerTest와 같은 방식)
// @Transactional: 테스트마다 DB에 저장한 내용을 되돌린다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CommentControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private CommentRepository commentRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	void 댓글을_달면_저장되고_그_글의_댓글_자리로_이동한다() throws Exception {
		Post post = savePost();

		mockMvc.perform(post("/posts/" + post.getId() + "/comments")
				.param("nickname", "댓글러")
				.param("password", "1234")
				.param("content", "  맛있겠다  "))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/posts/" + post.getId() + "#comments"));

		Comment saved = latestComment();
		assertThat(saved.getPostId()).isEqualTo(post.getId());
		assertThat(saved.getNickname()).isEqualTo("댓글러");
		// 앞뒤 공백은 지우고 저장한다
		assertThat(saved.getContent()).isEqualTo("맛있겠다");
		assertThat(saved.getStatus()).isEqualTo(PostStatus.ACTIVE);
		// 비밀번호는 해시로만 저장된다
		assertThat(saved.getPasswordHash()).isNotEqualTo("1234");
		assertThat(passwordEncoder.matches("1234", saved.getPasswordHash())).isTrue();
	}

	@Test
	void 상세_화면은_댓글을_오래된_순으로_넘긴다() throws Exception {
		Post post = savePost();
		Comment first = saveComment(post, "첫 댓글");
		Comment second = saveComment(post, "둘째 댓글");

		CommentPage page = commentsOf(get("/posts/" + post.getId()));

		assertThat(page.totalCount()).isEqualTo(2);
		assertThat(page.nextCursor()).isNull();
		assertThat(page.comments()).extracting(CommentView::id).containsExactly(first.getId(), second.getId());
		assertThat(page.comments().get(0).content()).isEqualTo("첫 댓글");
	}

	@Test
	void 내용이_비면_저장하지_않고_상세로_돌아가_댓글_칸에_메시지를_넘긴다() throws Exception {
		Post post = savePost();

		mockMvc.perform(post("/posts/" + post.getId() + "/comments")
				.param("nickname", "댓글러")
				.param("password", "1234")
				.param("content", "   "))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/posts/" + post.getId() + "#comments"))
				// 입력값과 검사 결과가 flash로 상세 화면에 넘어가야 한다 (그래야 칸이 채워지고 메시지가 보인다)
				.andExpect(flash().attributeExists("commentForm"))
				.andExpect(flash().attributeExists(BindingResult.MODEL_KEY_PREFIX + "commentForm"));

		assertThat(commentRepository.countByPostIdAndStatus(post.getId(), PostStatus.ACTIVE)).isZero();
	}

	@Test
	void 비밀번호가_4자보다_짧으면_저장하지_않는다() throws Exception {
		Post post = savePost();

		mockMvc.perform(post("/posts/" + post.getId() + "/comments")
				.param("nickname", "댓글러")
				.param("password", "123")
				.param("content", "짧은 비밀번호"))
				.andExpect(flash().attributeExists(BindingResult.MODEL_KEY_PREFIX + "commentForm"));

		assertThat(commentRepository.countByPostIdAndStatus(post.getId(), PostStatus.ACTIVE)).isZero();
	}

	@Test
	void 삭제된_글에는_댓글을_달_수_없다() throws Exception {
		Post post = savePost();
		post.deleteByAuthor();
		postRepository.save(post);

		mockMvc.perform(post("/posts/" + post.getId() + "/comments")
				.param("nickname", "댓글러")
				.param("password", "1234")
				.param("content", "늦었다"))
				.andExpect(status().isNotFound())
				.andExpect(view().name("common/error"));
	}

	@Test
	void 비밀번호가_맞으면_상태만_바꿔_삭제하고_더는_보이지_않는다() throws Exception {
		Post post = savePost();
		Comment comment = saveComment(post, "지울 댓글");

		mockMvc.perform(post("/posts/" + post.getId() + "/comments/" + comment.getId() + "/delete").param("password", "1234"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/posts/" + post.getId() + "#comments"));

		// DB에는 남아 있고 상태와 삭제 시각만 바뀐다
		Comment deleted = commentRepository.findById(comment.getId()).orElseThrow();
		assertThat(deleted.getStatus()).isEqualTo(PostStatus.DELETED_BY_AUTHOR);
		assertThat(deleted.getDeletedAt()).isNotNull();

		// 상세 화면의 댓글 목록과 개수에서 빠진다
		CommentPage page = commentsOf(get("/posts/" + post.getId()));
		assertThat(page.comments()).isEmpty();
		assertThat(page.totalCount()).isZero();
	}

	@Test
	void 비밀번호가_틀리면_삭제하지_않고_그_댓글_자리로_돌아가_메시지를_넘긴다() throws Exception {
		Post post = savePost();
		Comment comment = saveComment(post, "안 지워질 댓글");

		mockMvc.perform(post("/posts/" + post.getId() + "/comments/" + comment.getId() + "/delete").param("password", "0000"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/posts/" + post.getId() + "#comment-" + comment.getId()))
				.andExpect(flash().attribute("commentDeleteError", ErrorCode.INVALID_PASSWORD.getMessage()))
				.andExpect(flash().attribute("commentDeleteErrorId", comment.getId()));

		assertThat(commentRepository.findById(comment.getId()).orElseThrow().getStatus()).isEqualTo(PostStatus.ACTIVE);
	}

	@Test
	void 다른_글의_댓글은_주소를_바꿔도_지울_수_없다() throws Exception {
		Post post = savePost();
		Post otherPost = savePost();
		Comment comment = saveComment(post, "다른 글의 댓글");

		// otherPost의 주소로 post의 댓글을 지우려 하면 댓글을 찾지 못한 것으로 본다 (404)
		mockMvc.perform(post("/posts/" + otherPost.getId() + "/comments/" + comment.getId() + "/delete").param("password", "1234"))
				.andExpect(status().isNotFound());

		assertThat(commentRepository.findById(comment.getId()).orElseThrow().getStatus()).isEqualTo(PostStatus.ACTIVE);
	}

	@Test
	void 댓글이_21개면_20개와_더_보기_위치를_넘기고_이어서_볼_수_있다() throws Exception {
		Post post = savePost();
		for (int i = 1; i <= 21; i++) {
			saveComment(post, "댓글 " + i);
		}

		CommentPage firstPage = commentsOf(get("/posts/" + post.getId()));
		assertThat(firstPage.comments()).hasSize(20);
		assertThat(firstPage.totalCount()).isEqualTo(21);
		// 다음에 이어서 볼 위치 = 이번 화면의 마지막(20번째) 댓글 번호
		assertThat(firstPage.nextCursor()).isEqualTo(firstPage.comments().get(19).id());

		CommentPage secondPage = commentsOf(get("/posts/" + post.getId()).param("commentCursor", String.valueOf(firstPage.nextCursor())));
		assertThat(secondPage.comments()).hasSize(1);
		assertThat(secondPage.comments().get(0).content()).isEqualTo("댓글 21");
		assertThat(secondPage.nextCursor()).isNull();
	}

	@Test
	void 목록_카드는_글마다_댓글_수를_넘긴다() throws Exception {
		Post withComments = savePost();
		saveComment(withComments, "하나");
		saveComment(withComments, "둘");
		// 삭제된 댓글은 세지 않는다
		Comment deleted = saveComment(withComments, "지운 것");
		deleted.deleteByAuthor();
		commentRepository.save(deleted);
		Post without = savePost();

		MvcResult result = mockMvc.perform(get("/")).andExpect(status().isOk()).andReturn();
		FeedPage feed = (FeedPage) result.getModelAndView().getModel().get("feed");

		assertThat(feed.posts().stream().filter(p -> p.id().equals(withComments.getId())).findFirst().orElseThrow().commentCount()).isEqualTo(2);
		assertThat(feed.posts().stream().filter(p -> p.id().equals(without.getId())).findFirst().orElseThrow().commentCount()).isZero();
	}

	// 상세 화면 요청을 보내고, Model에 담긴 댓글 부분(CommentPage)을 꺼낸다
	private CommentPage commentsOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
		MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
		return (CommentPage) result.getModelAndView().getModel().get("comments");
	}

	// 테스트용 글을 DB에 바로 저장한다 (사진 파일은 만들지 않고 이름만 넣는다)
	private Post savePost() {
		return postRepository.save(new Post("테스터", passwordEncoder.encode("1234"), null, "test.jpg", "test-thumb.jpg", null));
	}

	// 비밀번호 1234인 테스트용 댓글
	private Comment saveComment(Post post, String content) {
		return commentRepository.save(new Comment(post.getId(), "댓글러", passwordEncoder.encode("1234"), content));
	}

	private Comment latestComment() {
		List<Comment> comments = commentRepository.findAll(Sort.by(Sort.Direction.DESC, "id"));
		return comments.get(0);
	}
}
