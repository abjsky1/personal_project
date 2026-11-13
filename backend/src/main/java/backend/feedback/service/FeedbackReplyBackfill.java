package backend.feedback.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import backend.feedback.model.entity.Feedback;
import backend.feedback.model.entity.FeedbackReply;
import backend.feedback.model.repository.FeedbackReplyRepository;
import backend.feedback.model.repository.FeedbackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 예전 답변 옮기기 (JM-77). 서버가 켜질 때마다 불리지만, 한 번 옮기고 나면 할 일이 없다.
// JM-77 전에는 운영자 답변이 feedbacks.admin_reply 한 칸이었다. 그 답변을 새 테이블 feedback_replies로 옮긴다.
// - 아직 옮기지 않은 글만 고른다 (FeedbackRepository.findWithOldReplyNotMoved) → 서버가 여러 번 켜져도 두 번 옮기지 않는다
// - 답변을 다시 달아서 덮어써졌던 예전 답변은 DB에 남아 있지 않다. 그때 게시판에 보였던 글을 아래 OVERWRITTEN에 적어 두고 함께 넣는다
// - 해요체로 달려 있던 답변 둘은 옮기면서 문어체로 고쳐 넣는다 (아래 REWORDED, 운영자 요청)
// - 옮기다 실패해도 서버는 그대로 켜진다 (답변 옮기기 때문에 사이트가 서면 안 된다). 다음에 켜질 때 다시 한다
// ApplicationRunner: 서버가 다 켜진 직후에 스프링이 run()을 한 번 불러 준다
// 한 번 쓰고 지울 코드다: 운영 DB에서 옮겨진 것을 확인한 뒤에는 이 클래스와 Feedback의 adminReply, repliedAt을 지워도 된다
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedbackReplyBackfill implements ApplicationRunner {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	// 덮어써져서 DB에 남아 있지 않은 예전 답변 (운영 DB의 3번, 9번 글). 시각은 그때 게시판에 보였던 한국 시각이다 (분까지)
	// currentRepliedAt: 그 글에 마지막으로 남아 있는 답변의 시각. 이것이 맞는 글에만 넣는다
	//   → 번호만 같은 다른 글(개발 DB의 3번, 9번 글)에는 들어가지 않는다
	// 잘못 달았던 답변은 적지 않았다: 6번 글에 들어갔던 7번 글의 답변(11월 6일), 7번 글에 같은 글을 다시 보낸 것
	private static final List<Overwritten> OVERWRITTEN = List.of(
			new Overwritten(3L, "2026-11-07T15:24", List.of(
					new OldReply("2026-11-06T23:33",
							"여러칸이 뜰 수 있도록 업데이트 목록에 추가하도록 하겠습니다! 커뮤니티 공간은 현재 댓글로 만들어 놓았는데 새로운 공간을 만들어보도록 하겠습니다. 피드백 감사합니다 :)"))),
			new Overwritten(9L, "2026-11-11T21:37", List.of(
					new OldReply("2026-11-09T12:59",
							"댓글에 댓글 다는 기능을 업데이트 목록에 추가하도록 하겠습니다. 피드백 감사합니다 :)"),
					new OldReply("2026-11-11T13:21",
							"댓글에 답글을 달 수 있도록 업데이트했습니다. 좋아요 기능은 업데이트 목록에 남겨 두었습니다. 피드백 감사합니다 :)"))));

	// 옮기면서 글을 고쳐 넣는 답변 (운영자 요청, 2026-11-13).
	// 답변 글의 규칙: 문어체(~습니다)로 쓰고, 한 글의 첫 답변은 "피드백 감사합니다 :)"로 끝낸다.
	// 해요체로 달려 있던 답변 둘(운영 DB의 1번 글, 3번 글의 마지막 답변)이 대상이다.
	// 앞의 글과 글자 하나까지 같은 답변만 뒤의 글로 바꿔 넣는다 → 다른 답변이나 다른 DB의 글은 건드리지 않는다.
	// 답변을 단 시각은 그대로 둔다. 1번 글은 첫 답변이라 끝인사를 붙였고, 3번 글은 둘째 답변이라 붙이지 않았다
	private static final Map<String, String> REWORDED = Map.of(
			"네, 누구나 볼 수 있는 공개 게시판이에요. 로그인 없이 쓰고, 삭제는 쓸 때 정한 비밀번호로 해요.",
			"네, 누구나 볼 수 있는 공개 게시판입니다. 로그인 없이 쓰고, 삭제는 쓸 때 정한 비밀번호로 할 수 있습니다. 피드백 감사합니다 :)",
			"요청하신 대로 휴대폰에서 피드가 한 줄에 세 장으로 보이게 바꿨어요. 새로고침해서 확인해 보세요! 함께 적어 주신 소통 공간은 따로 고민 중이에요.",
			"요청하신 대로 휴대폰에서 피드가 한 줄에 세 장으로 보이게 바꿨습니다. 함께 적어 주신 소통 공간은 따로 고민 중입니다. 조만간 업데이트 하겠습니다.");

	private final FeedbackRepository feedbackRepository;
	private final FeedbackReplyRepository feedbackReplyRepository;

	@Override
	public void run(ApplicationArguments args) {
		try {
			int moved = moveOldReplies();
			if (moved > 0) {
				log.info("Old feedback replies moved: replies={}", moved);
			}
		} catch (RuntimeException e) {
			log.error("Moving old feedback replies failed. The server keeps running and tries again at the next start.", e);
		}
	}

	// 아직 옮기지 않은 글의 예전 답변을 모두 옮기고, 옮긴 답변의 개수를 돌려준다.
	// saveAll은 한 트랜잭션이라서, 한 글의 답변들은 모두 들어가거나 하나도 안 들어간다 (중간에 끊겨도 반만 옮겨진 글이 생기지 않는다)
	int moveOldReplies() {
		int moved = 0;
		for (Feedback feedback : feedbackRepository.findWithOldReplyNotMoved()) {
			// 답변 시각이 비어 있는 일은 없지만, 비어 있으면 글을 쓴 시각으로 둔다
			Instant repliedAt = feedback.getRepliedAt() != null ? feedback.getRepliedAt() : feedback.getCreatedAt();
			List<FeedbackReply> replies = repliesOf(feedback.getId(), feedback.getAdminReply(), repliedAt);
			feedbackReplyRepository.saveAll(replies);
			moved += replies.size();
		}
		return moved;
	}

	// 한 글의 예전 답변을 달린 순서대로 만든다: 덮어써졌던 답변들(있으면) → 마지막으로 남아 있는 답변.
	// 저장할 때 이 순서대로 번호가 붙고, 화면은 번호순으로 보여 준다.
	// 남아 있는 답변이 REWORDED에 있는 글이면 고친 글로 넣는다 (없으면 그대로)
	static List<FeedbackReply> repliesOf(Long feedbackId, String currentReply, Instant currentRepliedAt) {
		List<FeedbackReply> replies = new ArrayList<>();
		LocalDateTime currentMinute = LocalDateTime.ofInstant(currentRepliedAt, KST).truncatedTo(ChronoUnit.MINUTES);
		for (Overwritten overwritten : OVERWRITTEN) {
			if (overwritten.feedbackId().equals(feedbackId) && LocalDateTime.parse(overwritten.currentRepliedAt()).equals(currentMinute)) {
				for (OldReply old : overwritten.older()) {
					replies.add(new FeedbackReply(feedbackId, old.content(), LocalDateTime.parse(old.repliedAt()).atZone(KST).toInstant()));
				}
			}
		}
		replies.add(new FeedbackReply(feedbackId, REWORDED.getOrDefault(currentReply, currentReply), currentRepliedAt));
		return replies;
	}

	private record Overwritten(Long feedbackId, String currentRepliedAt, List<OldReply> older) {
	}

	private record OldReply(String repliedAt, String content) {
	}
}
