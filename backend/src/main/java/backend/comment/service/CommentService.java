package backend.comment.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import backend.comment.model.dto.CommentCount;
import backend.comment.model.dto.CommentCreateForm;
import backend.comment.model.dto.CommentPage;
import backend.comment.model.dto.CommentView;
import backend.comment.model.entity.Comment;
import backend.comment.model.repository.CommentRepository;
import backend.common.error.BusinessException;
import backend.common.error.ErrorCode;
import backend.post.model.entity.PostStatus;
import backend.post.model.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 댓글 기능의 실제 처리 (PostService와 같은 역할)
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

	// 한 번에 보여 줄 댓글 수 (기획서 5절)
	private static final int PAGE_SIZE = 20;

	private final CommentRepository commentRepository;
	private final PostRepository postRepository;
	private final PasswordEncoder passwordEncoder;

	// 댓글 저장. 없거나 삭제된 글에는 달 수 없다 (POST_NOT_FOUND → 404 화면).
	@Transactional
	public Long create(Long postId, CommentCreateForm form) {
		requireActivePost(postId);

		Comment saved = commentRepository.save(new Comment(
				postId,
				form.getNickname().strip(),
				passwordEncoder.encode(form.getPassword()),
				form.getContent().strip()));
		log.info("Comment created: postId={}, commentId={}", postId, saved.getId());
		return saved.getId();
	}

	// 한 글의 댓글 목록. cursor가 없으면 처음부터, 있으면 그 번호 다음 댓글부터 (오래된 순)
	@Transactional(readOnly = true)
	public CommentPage getComments(Long postId, Long cursor) {
		// 처음이면 "0보다 큰 번호" = 첫 댓글부터
		long after = (cursor == null) ? 0 : cursor;

		// 21개를 가져와 21번째가 있으면 "더 보기"를 보여 준다 (PostService.getFeed와 같은 방식)
		List<Comment> comments = commentRepository.findByPostIdAndStatusAndIdGreaterThanOrderByIdAsc(
				postId, PostStatus.ACTIVE, after, Limit.of(PAGE_SIZE + 1));

		boolean hasNext = comments.size() > PAGE_SIZE;
		List<Comment> pageComments = hasNext ? comments.subList(0, PAGE_SIZE) : comments;
		Long nextCursor = hasNext ? pageComments.get(pageComments.size() - 1).getId() : null;

		long totalCount = commentRepository.countByPostIdAndStatus(postId, PostStatus.ACTIVE);
		return new CommentPage(pageComments.stream().map(CommentView::from).toList(), nextCursor, totalCount);
	}

	// 글 여러 개의 댓글 수를 "글 번호 → 개수" 지도로 돌려준다 (목록 카드용). 댓글이 없는 글은 지도에 없다.
	@Transactional(readOnly = true)
	public Map<Long, Long> countByPostIds(Collection<Long> postIds) {
		if (postIds.isEmpty()) {
			return Map.of();
		}
		return commentRepository.countByPostIds(postIds, PostStatus.ACTIVE).stream()
				.collect(Collectors.toMap(CommentCount::getPostId, CommentCount::getCount));
	}

	// 작성자 삭제: 비밀번호가 맞으면 상태만 DELETED_BY_AUTHOR로 바꾼다.
	// 댓글 번호와 글 번호가 함께 맞아야 찾는다 (주소의 글 번호와 다른 글의 댓글은 지우지 못하게)
	@Transactional
	public void deleteByAuthor(Long postId, Long commentId, String password) {
		Comment comment = commentRepository.findByIdAndPostIdAndStatus(commentId, postId, PostStatus.ACTIVE)
				.orElseThrow(() -> new BusinessException(ErrorCode.COMMENT_NOT_FOUND));

		if (!passwordEncoder.matches(password, comment.getPasswordHash())) {
			// 입력한 비밀번호는 로그에 남기지 않는다 (PostService와 같음)
			log.warn("Password mismatch: commentId={}", commentId);
			throw new BusinessException(ErrorCode.INVALID_PASSWORD);
		}

		comment.deleteByAuthor();
		log.info("Comment deleted by author: commentId={}, postId={}", commentId, postId);
	}

	// ACTIVE인 글이 아니면 POST_NOT_FOUND
	private void requireActivePost(Long postId) {
		if (postRepository.findByIdAndStatus(postId, PostStatus.ACTIVE).isEmpty()) {
			throw new BusinessException(ErrorCode.POST_NOT_FOUND);
		}
	}
}
