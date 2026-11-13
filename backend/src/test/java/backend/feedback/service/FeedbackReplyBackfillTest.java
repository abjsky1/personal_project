package backend.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import backend.feedback.model.entity.Feedback;
import backend.feedback.model.entity.FeedbackReply;
import backend.feedback.model.repository.FeedbackReplyRepository;
import backend.feedback.model.repository.FeedbackRepository;
import backend.post.model.entity.PostStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

// 예전 답변 옮기기(JM-77)를 확인하는 테스트
// 앞의 둘은 DB에 예전 방식의 답변을 만들어 놓고 실제로 옮겨 본다. 뒤의 넷은 "어떤 답변들을 어떤 순서로 만들지"만 본다 (DB를 쓰지 않는다)
@SpringBootTest
@Transactional
class FeedbackReplyBackfillTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	@Autowired
	private FeedbackReplyBackfill backfill;

	@Autowired
	private FeedbackRepository feedbackRepository;

	@Autowired
	private FeedbackReplyRepository feedbackReplyRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@PersistenceContext
	private EntityManager entityManager;

	@Test
	void 예전_칸의_답변을_새_테이블로_옮기고_다시_돌려도_두_번_옮기지_않는다() {
		Instant repliedAt = Instant.parse("2026-11-07T05:55:30Z");
		Feedback withOldReply = saveWithOldReply("예전 방식으로 단 답변", repliedAt);
		Feedback withoutReply = feedbackRepository.save(new Feedback("사용자", passwordEncoder.encode("1234"), "답변 없는 글", "내용"));

		// 서버가 켜질 때 이미 한 번 돌았으므로, 지금 옮길 것은 방금 만든 글의 답변 하나뿐이다
		assertThat(backfill.moveOldReplies()).isEqualTo(1);
		// DB에 실제로 들어간 값을 다시 읽어서 확인한다
		entityManager.flush();
		entityManager.clear();

		List<FeedbackReply> moved = repliesOf(withOldReply);
		assertThat(moved).hasSize(1);
		assertThat(moved.get(0).getContent()).isEqualTo("예전 방식으로 단 답변");
		// 답변 시각은 옮긴 시각이 아니라 예전에 답변을 단 시각 그대로
		assertThat(moved.get(0).getCreatedAt()).isEqualTo(repliedAt);
		assertThat(repliesOf(withoutReply)).isEmpty();

		// 다시 돌려도 더 옮기지 않는다
		assertThat(backfill.moveOldReplies()).isZero();
		assertThat(repliesOf(withOldReply)).hasSize(1);
	}

	@Test
	void 옮긴_답변을_운영자가_지워도_다시_옮겨_오지_않는다() {
		Feedback feedback = saveWithOldReply("옮긴 뒤에 지울 답변", Instant.parse("2026-11-07T05:55:30Z"));
		assertThat(backfill.moveOldReplies()).isEqualTo(1);

		FeedbackReply moved = repliesOf(feedback).get(0);
		moved.deleteByAdmin();
		feedbackReplyRepository.saveAndFlush(moved);

		assertThat(backfill.moveOldReplies()).isZero();
		assertThat(repliesOf(feedback)).isEmpty();
	}

	@Test
	void 운영_9번_글은_덮어써졌던_답변_둘을_시간_순서대로_앞에_넣는다() {
		Instant current = kst("2026-11-11T21:37:48");

		List<FeedbackReply> replies = FeedbackReplyBackfill.repliesOf(9L, "지금 남아 있는 답변", current);

		assertThat(replies).extracting(FeedbackReply::getContent).containsExactly(
				"댓글에 댓글 다는 기능을 업데이트 목록에 추가하도록 하겠습니다. 피드백 감사합니다 :)",
				"댓글에 답글을 달 수 있도록 업데이트했습니다. 좋아요 기능은 업데이트 목록에 남겨 두었습니다. 피드백 감사합니다 :)",
				"지금 남아 있는 답변");
		assertThat(replies).extracting(FeedbackReply::getCreatedAt)
				.containsExactly(kst("2026-11-09T12:59:00"), kst("2026-11-11T13:21:00"), current);
		assertThat(replies).allSatisfy(reply -> {
			assertThat(reply.getFeedbackId()).isEqualTo(9L);
			assertThat(reply.getStatus()).isEqualTo(PostStatus.ACTIVE);
		});
	}

	@Test
	void 운영_3번_글은_덮어써졌던_답변_하나를_앞에_넣는다() {
		Instant current = kst("2026-11-07T15:24:05");

		List<FeedbackReply> replies = FeedbackReplyBackfill.repliesOf(3L, "지금 남아 있는 답변", current);

		assertThat(replies).hasSize(2);
		assertThat(replies.get(0).getContent()).startsWith("여러칸이 뜰 수 있도록");
		assertThat(replies.get(0).getCreatedAt()).isEqualTo(kst("2026-11-06T23:33:00"));
		assertThat(replies.get(1).getContent()).isEqualTo("지금 남아 있는 답변");
		assertThat(replies.get(1).getCreatedAt()).isEqualTo(current);
	}

	// 개발 DB의 9번 글처럼 번호만 같은 다른 글에 운영 게시판의 답변이 들어가면 안 된다
	@Test
	void 번호는_같아도_남아_있는_답변의_시각이_다르면_예전_답변을_넣지_않는다() {
		List<FeedbackReply> replies = FeedbackReplyBackfill.repliesOf(9L, "다른 글의 답변", kst("2026-11-11T21:38:00"));

		assertThat(replies).extracting(FeedbackReply::getContent).containsExactly("다른 글의 답변");
	}

	@Test
	void 덮어써진_적이_없는_글은_남아_있는_답변_하나만_옮긴다() {
		Instant current = kst("2026-11-10T15:30:12");

		List<FeedbackReply> replies = FeedbackReplyBackfill.repliesOf(6L, "지금 남아 있는 답변", current);

		assertThat(replies).hasSize(1);
		assertThat(replies.get(0).getContent()).isEqualTo("지금 남아 있는 답변");
		assertThat(replies.get(0).getCreatedAt()).isEqualTo(current);
	}

	// 한국 시각으로 적은 글자를 Instant로
	private static Instant kst(String localDateTime) {
		return LocalDateTime.parse(localDateTime).atZone(KST).toInstant();
	}

	// 예전 방식의 답변이 달린 피드백을 만든다.
	// 예전 코드가 답변을 달 때 하던 일(예전 칸 두 개에 값 넣기)을 그대로 한다. 지금은 그 메서드가 없어서 테스트에서만 칸에 직접 넣는다
	private Feedback saveWithOldReply(String reply, Instant repliedAt) {
		Feedback feedback = feedbackRepository.save(new Feedback("사용자", passwordEncoder.encode("1234"), "예전에 답변 받은 글", "내용"));
		ReflectionTestUtils.setField(feedback, "adminReply", reply);
		ReflectionTestUtils.setField(feedback, "repliedAt", repliedAt);
		return feedbackRepository.saveAndFlush(feedback);
	}

	private List<FeedbackReply> repliesOf(Feedback feedback) {
		return feedbackReplyRepository.findByFeedbackIdAndStatusOrderByIdAsc(feedback.getId(), PostStatus.ACTIVE);
	}
}
