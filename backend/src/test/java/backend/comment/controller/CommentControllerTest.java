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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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

// 댓글 달기, 보기, 지우기 요청을 실제로 보내 보고 저장과 검사가 제대로 되는지 확인하는 테스트 (PostControllerTest와 같은 방식)
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

	@Test
	void 답글을_달면_달린_댓글_번호와_함께_저장하고_그_답글_자리로_이동한다() throws Exception {
		Post post = savePost();
		Comment parent = saveComment(post, "부모 댓글");

		MvcResult result = mockMvc.perform(replyRequest(post.getId(), parent.getId(), "  답글입니다  "))
				.andExpect(status().is3xxRedirection())
				.andReturn();

		Comment reply = latestComment();
		assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/posts/" + post.getId() + "#comment-" + reply.getId());
		assertThat(reply.getPostId()).isEqualTo(post.getId());
		assertThat(reply.getParentId()).isEqualTo(parent.getId());
		// 앞뒤 공백은 지우고 비밀번호는 해시로 저장한다 (댓글과 같음)
		assertThat(reply.getContent()).isEqualTo("답글입니다");
		assertThat(passwordEncoder.matches("1234", reply.getPasswordHash())).isTrue();
	}

	@Test
	void 답글은_달린_댓글_아래에_오래된_순으로_넘기고_댓글_수에_센다() throws Exception {
		Post post = savePost();
		Comment parent = saveComment(post, "부모 댓글");
		Comment other = saveComment(post, "다른 댓글");
		Comment firstReply = saveReply(post, parent, "첫 답글");
		Comment secondReply = saveReply(post, parent, "둘째 답글");

		CommentPage page = commentsOf(get("/posts/" + post.getId()));

		// 답글은 목록에 따로 나오지 않고 달린 댓글 아래에 들어간다
		assertThat(page.comments()).extracting(CommentView::id).containsExactly(parent.getId(), other.getId());
		assertThat(page.comments().get(0).replies()).extracting(CommentView::id).containsExactly(firstReply.getId(), secondReply.getId());
		assertThat(page.comments().get(0).replies().get(0).parentId()).isEqualTo(parent.getId());
		assertThat(page.comments().get(1).replies()).isEmpty();
		// "댓글 N개"에는 답글도 들어간다
		assertThat(page.totalCount()).isEqualTo(4);
	}

	@Test
	void 답글에는_답글을_달_수_없다() throws Exception {
		Post post = savePost();
		Comment parent = saveComment(post, "부모 댓글");
		Comment reply = saveReply(post, parent, "답글");

		mockMvc.perform(replyRequest(post.getId(), reply.getId(), "답글의 답글"))
				.andExpect(status().isNotFound());

		assertThat(latestComment().getId()).isEqualTo(reply.getId());
	}

	@Test
	void 지운_댓글에는_답글을_달_수_없다() throws Exception {
		Post post = savePost();
		Comment parent = saveComment(post, "지운 댓글");
		parent.deleteByAuthor();
		commentRepository.save(parent);

		mockMvc.perform(replyRequest(post.getId(), parent.getId(), "늦은 답글"))
				.andExpect(status().isNotFound());

		assertThat(latestComment().getId()).isEqualTo(parent.getId());
	}

	@Test
	void 다른_글의_댓글에는_주소를_바꿔도_답글을_달_수_없다() throws Exception {
		Post post = savePost();
		Post otherPost = savePost();
		Comment parent = saveComment(post, "다른 글의 댓글");

		mockMvc.perform(replyRequest(otherPost.getId(), parent.getId(), "답글"))
				.andExpect(status().isNotFound());

		assertThat(latestComment().getId()).isEqualTo(parent.getId());
	}

	@Test
	void 삭제된_글의_댓글에는_답글을_달_수_없다() throws Exception {
		Post post = savePost();
		Comment parent = saveComment(post, "부모 댓글");
		post.deleteByAuthor();
		postRepository.save(post);

		mockMvc.perform(replyRequest(post.getId(), parent.getId(), "답글"))
				.andExpect(status().isNotFound());

		assertThat(latestComment().getId()).isEqualTo(parent.getId());
	}

	@Test
	void 답글_내용이_비면_저장하지_않고_그_댓글_자리로_돌아가_메시지를_넘긴다() throws Exception {
		Post post = savePost();
		Comment parent = saveComment(post, "부모 댓글");

		mockMvc.perform(replyRequest(post.getId(), parent.getId(), "   "))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/posts/" + post.getId() + "#comment-" + parent.getId()))
				.andExpect(flash().attribute("replyErrorId", parent.getId()))
				.andExpect(flash().attribute("replyErrors", List.of("댓글 내용을 입력해 주세요.")));

		assertThat(latestComment().getId()).isEqualTo(parent.getId());
	}

	@Test
	void 더_보기_화면에서_답글을_달면_그_화면으로_돌아온다() throws Exception {
		Post post = savePost();
		Comment parent = saveComment(post, "부모 댓글");

		MvcResult result = mockMvc.perform(replyRequest(post.getId(), parent.getId(), "답글").param("commentCursor", "7"))
				.andExpect(status().is3xxRedirection())
				.andReturn();

		assertThat(result.getResponse().getRedirectedUrl())
				.isEqualTo("/posts/" + post.getId() + "?commentCursor=7#comment-" + latestComment().getId());
	}

	@Test
	void 답글이_있는_댓글을_작성자가_지우면_작성자_안내_글과_답글이_남는다() throws Exception {
		Post post = savePost();
		Comment parent = saveComment(post, "지울 댓글");
		Comment reply = saveReply(post, parent, "남을 답글");

		mockMvc.perform(post("/posts/" + post.getId() + "/comments/" + parent.getId() + "/delete").param("password", "1234"))
				.andExpect(status().is3xxRedirection());

		CommentPage page = commentsOf(get("/posts/" + post.getId()));
		CommentView shown = page.comments().get(0);
		assertThat(shown.id()).isEqualTo(parent.getId());
		assertThat(shown.deletedMessage()).isEqualTo("작성자에 의해 삭제된 댓글입니다.");
		// 지운 댓글의 닉네임과 내용은 화면으로 넘기지 않는다
		assertThat(shown.nickname()).isNull();
		assertThat(shown.content()).isNull();
		assertThat(shown.replies()).extracting(CommentView::id).containsExactly(reply.getId());
		// 지운 댓글은 세지 않고 답글만 센다
		assertThat(page.totalCount()).isEqualTo(1);
	}

	@Test
	void 답글이_있는_댓글을_운영자가_지우면_관리자_안내_글과_답글이_남는다() throws Exception {
		Post post = savePost();
		Comment parent = saveComment(post, "지울 댓글");
		Comment reply = saveReply(post, parent, "남을 답글");
		parent.deleteByAdmin();
		commentRepository.save(parent);

		CommentView shown = commentsOf(get("/posts/" + post.getId())).comments().get(0);

		assertThat(shown.deletedMessage()).isEqualTo("관리자에 의해 삭제된 댓글입니다.");
		assertThat(shown.content()).isNull();
		assertThat(shown.replies()).extracting(CommentView::id).containsExactly(reply.getId());
	}

	@Test
	void 답글까지_모두_지우면_지운_댓글의_자리도_사라진다() throws Exception {
		Post post = savePost();
		Comment parent = saveComment(post, "지울 댓글");
		Comment reply = saveReply(post, parent, "지울 답글");
		parent.deleteByAuthor();
		reply.deleteByAuthor();
		commentRepository.saveAll(List.of(parent, reply));

		CommentPage page = commentsOf(get("/posts/" + post.getId()));

		assertThat(page.comments()).isEmpty();
		assertThat(page.totalCount()).isZero();
	}

	@Test
	void 답글을_지우면_그_답글만_사라지고_댓글과_다른_답글은_남는다() throws Exception {
		Post post = savePost();
		Comment parent = saveComment(post, "부모 댓글");
		Comment removed = saveReply(post, parent, "지울 답글");
		Comment kept = saveReply(post, parent, "남을 답글");

		mockMvc.perform(post("/posts/" + post.getId() + "/comments/" + removed.getId() + "/delete").param("password", "1234"))
				.andExpect(status().is3xxRedirection());

		CommentView shown = commentsOf(get("/posts/" + post.getId())).comments().get(0);
		assertThat(shown.deletedMessage()).isNull();
		assertThat(shown.content()).isEqualTo("부모 댓글");
		assertThat(shown.replies()).extracting(CommentView::id).containsExactly(kept.getId());
	}

	@Test
	void 답글은_한_화면_20개에_세지_않는다() throws Exception {
		Post post = savePost();
		Comment parent = saveComment(post, "부모 댓글");
		for (int i = 1; i <= 21; i++) {
			saveReply(post, parent, "답글 " + i);
		}

		CommentPage page = commentsOf(get("/posts/" + post.getId()));

		assertThat(page.comments()).hasSize(1);
		assertThat(page.comments().get(0).replies()).hasSize(21);
		assertThat(page.nextCursor()).isNull();
		assertThat(page.totalCount()).isEqualTo(22);
	}

	@Test
	void 목록_카드의_댓글_수에_답글도_센다() throws Exception {
		Post post = savePost();
		Comment parent = saveComment(post, "부모 댓글");
		saveReply(post, parent, "답글");

		MvcResult result = mockMvc.perform(get("/")).andExpect(status().isOk()).andReturn();
		FeedPage feed = (FeedPage) result.getModelAndView().getModel().get("feed");

		assertThat(feed.posts().stream().filter(p -> p.id().equals(post.getId())).findFirst().orElseThrow().commentCount()).isEqualTo(2);
	}

	// 답글 쓰기 요청 (비밀번호 1234)
	private MockHttpServletRequestBuilder replyRequest(Long postId, Long commentId, String content) {
		return post("/posts/" + postId + "/comments/" + commentId + "/replies")
				.param("nickname", "답글러")
				.param("password", "1234")
				.param("content", content);
	}

	// 상세 화면 요청을 보내고, Model에 담긴 댓글 부분(CommentPage)을 꺼낸다
	private CommentPage commentsOf(MockHttpServletRequestBuilder request) throws Exception {
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

	// 비밀번호 1234인 테스트용 답글
	private Comment saveReply(Post post, Comment parent, String content) {
		return commentRepository.save(new Comment(post.getId(), parent.getId(), "답글러", passwordEncoder.encode("1234"), content));
	}

	private Comment latestComment() {
		List<Comment> comments = commentRepository.findAll(Sort.by(Sort.Direction.DESC, "id"));
		return comments.get(0);
	}
}
