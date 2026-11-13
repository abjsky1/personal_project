package backend.feedback.service;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import backend.common.error.BusinessException;
import backend.common.error.ErrorCode;
import backend.common.web.PageNav;
import backend.feedback.model.dto.FeedbackCreateForm;
import backend.feedback.model.dto.FeedbackDetail;
import backend.feedback.model.dto.FeedbackPage;
import backend.feedback.model.dto.FeedbackSummary;
import backend.feedback.model.entity.Feedback;
import backend.feedback.model.entity.FeedbackProgress;
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

	// 목록: 최신순 20개씩 쪽 번호 방식 (PostService.getFeed와 같은 방식, JM-57)
	// 삭제된 글도 함께 읽는다 (JM-76): 목록에 번호와 "삭제된 피드백입니다."만 남겨서, 글이 지워져도 번호와 자리가 당겨지지 않게 한다.
	// 삭제된 글의 제목, 닉네임은 FeedbackSummary가 화면으로 넘기지 않는다. 상세(getDetail)는 지금처럼 404다
	@Transactional(readOnly = true)
	public FeedbackPage getPage(int requestedPage) {
		Page<Feedback> page = loadPage(Math.max(1, requestedPage));
		// 범위를 넘는 쪽 번호면 마지막 쪽을 다시 읽는다
		if (!page.hasContent() && page.getTotalPages() > 0) {
			page = loadPage(page.getTotalPages());
		}
		return new FeedbackPage(page.getContent().stream().map(FeedbackSummary::from).toList(),
				PageNav.of(page.getNumber() + 1, page.getTotalPages()));
	}

	private Page<Feedback> loadPage(int pageNumber) {
		return feedbackRepository.findAll(PageRequest.of(pageNumber - 1, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "id")));
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

	// ---- 운영자용 (관리자 API) ----

	@Transactional
	public void deleteByAdmin(Long id) {
		Feedback feedback = findActive(id);
		feedback.deleteByAdmin();
		log.info("Admin deleted feedback: feedbackId={}", id);
	}

	// 처리 상태 바꾸기와 답변 달기. null인 쪽은 건드리지 않는다. 둘 다 없으면 INVALID_REQUEST(400)
	@Transactional
	public FeedbackDetail updateByAdmin(Long id, FeedbackProgress progress, String reply) {
		boolean hasReply = reply != null && !reply.isBlank();
		if (progress == null && !hasReply) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}

		Feedback feedback = findActive(id);
		if (progress != null) {
			feedback.changeProgress(progress);
		}
		if (hasReply) {
			feedback.reply(reply.strip());
		}
		log.info("Admin updated feedback: feedbackId={}, progress={}, replied={}", id, feedback.getProgress(), hasReply);
		return FeedbackDetail.from(feedback);
	}

	private Feedback findActive(Long id) {
		return feedbackRepository.findByIdAndStatus(id, PostStatus.ACTIVE)
				.orElseThrow(() -> new BusinessException(ErrorCode.FEEDBACK_NOT_FOUND));
	}
}
