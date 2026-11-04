package backend.feedback.service;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import backend.common.error.BusinessException;
import backend.common.error.ErrorCode;
import backend.feedback.model.dto.FeedbackCreateForm;
import backend.feedback.model.dto.FeedbackDetail;
import backend.feedback.model.dto.FeedbackPage;
import backend.feedback.model.dto.FeedbackSummary;
import backend.feedback.model.entity.Feedback;
import backend.feedback.model.repository.FeedbackRepository;
import backend.post.model.entity.PostStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 피드백 게시판의 실제 처리 (PostService와 같은 역할. 사진이 없어서 훨씬 단순하다)
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackService {

	private static final int PAGE_SIZE = 20;

	private final FeedbackRepository feedbackRepository;
	private final PasswordEncoder passwordEncoder;

	// 저장. 저장된 번호를 돌려준다.
	@Transactional
	public Long create(FeedbackCreateForm form) {
		Feedback saved = feedbackRepository.save(new Feedback(
				form.getNickname().strip(),
				passwordEncoder.encode(form.getPassword()),
				form.getTitle().strip(),
				form.getContent().strip()));
		log.info("Feedback created: feedbackId={}", saved.getId());
		return saved.getId();
	}

	// 목록: 최신순 20개 + 더 보기 (PostService.getFeed와 같은 커서 방식)
	@Transactional(readOnly = true)
	public FeedbackPage getPage(Long cursor) {
		long before = (cursor == null) ? Long.MAX_VALUE : cursor;
		List<Feedback> feedbacks = feedbackRepository.findByStatusAndIdLessThanOrderByIdDesc(
				PostStatus.ACTIVE, before, Limit.of(PAGE_SIZE + 1));

		boolean hasNext = feedbacks.size() > PAGE_SIZE;
		List<Feedback> page = hasNext ? feedbacks.subList(0, PAGE_SIZE) : feedbacks;
		Long nextCursor = hasNext ? page.get(page.size() - 1).getId() : null;
		return new FeedbackPage(page.stream().map(FeedbackSummary::from).toList(), nextCursor);
	}

	// 상세. 없거나 삭제된 글이면 FEEDBACK_NOT_FOUND (404 화면)
	@Transactional(readOnly = true)
	public FeedbackDetail getDetail(Long id) {
		return FeedbackDetail.from(findActive(id));
	}

	// 작성자 삭제: 비밀번호가 맞으면 상태만 바꾼다
	@Transactional
	public void deleteByAuthor(Long id, String password) {
		Feedback feedback = findActive(id);
		if (!passwordEncoder.matches(password, feedback.getPasswordHash())) {
			log.warn("Password mismatch: feedbackId={}", id);
			throw new BusinessException(ErrorCode.INVALID_PASSWORD);
		}
		feedback.deleteByAuthor();
		log.info("Feedback deleted by author: feedbackId={}", id);
	}

	private Feedback findActive(Long id) {
		return feedbackRepository.findByIdAndStatus(id, PostStatus.ACTIVE)
				.orElseThrow(() -> new BusinessException(ErrorCode.FEEDBACK_NOT_FOUND));
	}
}
