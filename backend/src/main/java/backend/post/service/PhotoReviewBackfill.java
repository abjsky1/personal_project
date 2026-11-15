package backend.post.service;

import java.time.Instant;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import backend.analysis.model.entity.AnalysisStatus;
import backend.post.model.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 사진 검토를 시작하기 전에 올라온 글 표시하기 (JM-80). 서버가 켜질 때마다 불리지만, 한 번 표시하고 나면 할 일이 없다.
// 사진 검토(Post.isPhotoHeld)는 AI가 음식이 아니라고 본 글의 사진을 운영자가 올리기 전까지 가린다.
// 그런데 이 기능 전에 올라와 있던 그런 글(그때 실제 사이트에 7개)은 운영자가 이미 확인했다 (2026-11-15 운영자 결정:
// "지금 피드에 보이는 음식 아님 글은 가릴 필요 없고 앞으로 올라오는 글부터 확인"). 그래서 기준 시각 전에 올라온 글은 올린 것으로 표시한다.
// - 아직 표시하지 않은 글만 고른다 (PostRepository.approveHeldBefore) → 서버가 여러 번 켜져도 한 번만 바뀐다
// - 실패해도 서버는 그대로 켜진다. 다음에 켜질 때 다시 한다
// ApplicationRunner: 서버가 다 켜진 직후에 스프링이 run()을 한 번 불러 준다 (FeedbackReplyBackfill과 같은 방식)
// 한 번 쓰고 지울 코드다: 운영 DB에서 표시된 것을 확인한 뒤에는 이 클래스와 PostRepository.approveHeldBefore를 지워도 된다
@Slf4j
@Component
@RequiredArgsConstructor
public class PhotoReviewBackfill implements ApplicationRunner {

	// 사진 검토를 시작한 시각: 운영자가 위처럼 정한 2026-11-15 한국 시각 오후 2시. 이보다 뒤에 올라온 글부터 검토한다
	static final Instant REVIEW_START = Instant.parse("2026-11-15T05:00:00Z");

	private final PostRepository postRepository;

	@Override
	public void run(ApplicationArguments args) {
		try {
			int approved = approveOldHeldPhotos();
			if (approved > 0) {
				log.info("Photos posted before the photo review started marked as approved: posts={}", approved);
			}
		} catch (RuntimeException e) {
			log.error("Marking photos posted before the photo review failed. The server keeps running and tries again at the next start.", e);
		}
	}

	// 기준 시각 전에 올라온 글 중 AI가 음식이 아니라고 봤고 아직 올리지 않은 글을 올린 것으로 표시하고, 표시한 글 수를 돌려준다
	int approveOldHeldPhotos() {
		return postRepository.approveHeldBefore(REVIEW_START, Instant.now(), AnalysisStatus.NOT_FOOD);
	}
}
