package backend.post.service;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import backend.common.error.BusinessException;
import backend.common.error.ErrorCode;
import backend.image.ImageProcessor;
import backend.image.ImageStorage;
import backend.image.PhotoMetadata;
import backend.image.ProcessedImage;
import backend.location.model.entity.PostLocation;
import backend.location.model.repository.PostLocationRepository;
import backend.location.model.vo.GeoPoint;
import backend.post.model.dto.FeedPage;
import backend.post.model.dto.PostCreateForm;
import backend.post.model.dto.PostSummary;
import backend.post.model.entity.Post;
import backend.post.model.entity.PostStatus;
import backend.post.model.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 게시글 기능의 실제 처리(비즈니스 로직)를 맡는 클래스
// Controller는 요청을 받고 화면을 고르는 일만 하고, 저장·조회 같은 실제 일은 여기서 한다.
//
// @Service: Spring이 이 객체를 만들어 관리한다 (@Component와 같고, 역할을 이름으로 드러낸다)
// @RequiredArgsConstructor(Lombok): final 필드를 모두 받는 생성자를 컴파일할 때 자동으로 만들어 준다.
// → Spring이 그 생성자로 아래 객체들을 넣어 준다 (생성자 주입). 코드에 생성자가 안 보여도 실제로는 있다.
// @Slf4j(Lombok): 로그를 찍는 log 필드를 자동으로 만들어 준다. (LoggerFactory.getLogger(PostService.class)와 같다)
@Slf4j
@Service
@RequiredArgsConstructor
public class PostService {

	// 목록 한 번에 보여 줄 글 수
	private static final int PAGE_SIZE = 20;

	private final PostRepository postRepository;
	private final PostLocationRepository postLocationRepository;
	private final ImageProcessor imageProcessor;
	// 사진 저장소: 내 PC에서는 폴더(LocalImageStorage), 배포 서버에서는 S3(S3ImageStorage)가 들어온다
	private final ImageStorage imageStorage;
	private final PasswordEncoder passwordEncoder;
	// 코드로 트랜잭션 범위를 정하는 도구 (Spring Boot가 자동으로 만들어 둔다)
	private final TransactionTemplate transactionTemplate;

	// 글 저장. 저장된 글 번호를 돌려준다.
	// 이 메서드에는 @Transactional을 붙이지 않는다.
	// 사진 처리는 수백 ms가 걸리는데, 트랜잭션 안에서 하면 그동안 DB 연결을 붙잡고 있게 된다. (기획서 9.2절, LLM 호출과 같은 이유)
	// 그래서 사진 처리를 먼저 끝내고, DB에 저장하는 부분만 트랜잭션으로 묶는다(아래 transactionTemplate).
	public Long create(PostCreateForm form) {
		// 사진 검사·위치 읽기·회전 보정·EXIF 제거·크기 줄이기. 사진이 아니거나 너무 크면 BusinessException이 난다.
		ProcessedImage image = imageProcessor.process(form.getImage());

		// 큰 사진과 썸네일을 저장하고, 파일 이름(key)을 받는다.
		// (DB 저장이 실패하면 파일만 남을 수 있지만, 쓰이지 않는 파일일 뿐이라 MVP에서는 그대로 둔다)
		String imageKey = imageStorage.saveJpeg(image.main());
		String thumbnailKey = imageStorage.saveJpeg(image.thumbnail());

		// 위치·촬영 시각은 사용자가 동의했을 때만 쓴다. 동의하지 않았으면 사진에 있었더라도 빈 값으로 취급한다.
		boolean consent = form.isLocationConsent();
		PhotoMetadata metadata = consent ? image.metadata() : PhotoMetadata.EMPTY;

		// encode(): 비밀번호를 BCrypt 해시로 바꾼다. 같은 비밀번호도 매번 다른 해시가 나온다(salt).
		// 나중에 삭제할 때는 matches(입력한 비밀번호, 저장된 해시)로 맞는지만 확인한다.
		Post post = new Post(
				form.getNickname().strip(),
				passwordEncoder.encode(form.getPassword()),
				normalizeMemo(form.getMemo()),
				imageKey,
				thumbnailKey,
				metadata.takenAt());

		// 글과 위치, DB 저장 두 건을 하나의 트랜잭션으로 묶는다. (위치 저장이 실패하면 글 저장도 되돌린다)
		// @Transactional을 붙인 메서드를 따로 만들어 같은 클래스 안에서 부르면 트랜잭션이 걸리지 않는다.
		// (Spring은 클래스 바깥에서 들어오는 호출에만 트랜잭션을 씌우기 때문 = "self-invocation" 문제)
		// 그래서 코드로 직접 트랜잭션 범위를 정하는 TransactionTemplate을 쓴다. execute(...) 안이 트랜잭션 범위다.
		return transactionTemplate.execute(status -> {
			Post saved = postRepository.save(post);
			saveLocation(saved, consent, metadata);
			return saved.getId();
		});
	}

	// 동의했고 사진에 GPS 정보가 있으면 위치를 저장한다.
	// 좌표 값은 로그에도 절대 남기지 않는다. (로그가 유출돼도 사용자의 위치가 드러나지 않게)
	private void saveLocation(Post post, boolean consent, PhotoMetadata metadata) {
		if (!consent) {
			return;
		}
		if (!metadata.hasLocation()) {
			// 동의했지만 사진에 위치가 없는 경우. 아이폰 사파리처럼 업로드할 때 위치를 지우는 브라우저가 있어서,
			// 동의한 사람 중 실제로 위치가 들어오는 비율(수집률)을 로그로 세어 보려고 남긴다. (기획서 6.2절)
			log.info("Location consented but no GPS in photo: postId={}", post.getId());
			return;
		}

		GeoPoint point;
		try {
			point = GeoPoint.of(metadata.latitude(), metadata.longitude());
		} catch (IllegalArgumentException e) {
			// 범위를 벗어난 이상한 GPS 값이면 위치만 버리고 글은 그대로 저장한다.
			log.warn("Invalid GPS in photo: postId={}", post.getId());
			return;
		}

		postLocationRepository.save(new PostLocation(post, point, Instant.now()));
		log.info("Location saved: postId={}", post.getId());
	}

	// 목록 조회. cursor가 없으면 첫 화면, 있으면 그 번호 다음 글부터 가져온다.
	// readOnly = true: 읽기만 하는 작업이라고 알려서 불필요한 변경 확인을 건너뛴다.
	@Transactional(readOnly = true)
	public FeedPage getFeed(Long cursor) {
		// 첫 화면이면 "아주 큰 번호보다 작은 글" = 가장 최신 글부터
		long before = (cursor == null) ? Long.MAX_VALUE : cursor;

		// 20개가 아니라 21개를 가져와 본다. 21번째가 있으면 "더 보기"를 보여 주고, 화면에는 20개만 쓴다.
		List<Post> posts = postRepository.findByStatusAndIdLessThanOrderByIdDesc(
				PostStatus.ACTIVE, before, Limit.of(PAGE_SIZE + 1));

		boolean hasNext = posts.size() > PAGE_SIZE;
		List<Post> pagePosts = hasNext ? posts.subList(0, PAGE_SIZE) : posts;
		// 다음에 이어서 볼 위치 = 이번 화면의 마지막 글 번호
		Long nextCursor = hasNext ? pagePosts.get(pagePosts.size() - 1).getId() : null;

		// stream().map(...).toList(): 목록의 Post 하나하나를 PostSummary로 바꿔 새 목록을 만든다.
		return new FeedPage(pagePosts.stream().map(PostSummary::from).toList(), nextCursor);
	}

	// 상세 조회. 없거나 삭제된 글이면 POST_NOT_FOUND 예외를 던진다 → 에러 화면(404)이 나온다.
	// 상세 화면도 지금은 목록과 같은 값만 보여 주므로 PostSummary를 함께 쓴다. (S2에서 분석 결과가 붙으면 상세용 DTO를 따로 만든다)
	@Transactional(readOnly = true)
	public PostSummary getDetail(Long id) {
		return PostSummary.from(findActivePost(id));
	}

	// 작성자 삭제: 비밀번호가 맞으면 상태를 DELETED_BY_AUTHOR로 바꾼다. (DB에서 지우지 않는다)
	@Transactional
	public void deleteByAuthor(Long id, String password) {
		Post post = findActivePost(id);

		// matches(입력한 비밀번호, 저장된 해시): 입력값을 같은 방식으로 해시해서 저장된 해시와 같은지 비교한다.
		if (!passwordEncoder.matches(password, post.getPasswordHash())) {
			// 비밀번호 실패는 로그로 남긴다 (기획서 5절). 입력한 비밀번호 자체는 절대 로그에 남기지 않는다.
			log.warn("Password mismatch: postId={}", id);
			throw new BusinessException(ErrorCode.INVALID_PASSWORD);
		}

		// 상태만 바꾸면 메서드가 끝날 때(트랜잭션 종료) JPA가 UPDATE를 실행한다.
		post.deleteByAuthor();
		// 위치는 상태 변경이 아니라 완전히 지운다. 작성자가 글을 지우는 것을 위치 제공 동의 철회로 본다. (기획서 5.1절)
		// 위치가 없는 글이면 아무 일도 하지 않는다.
		postLocationRepository.deleteById(id);
		log.info("Post deleted by author: postId={}", id);
	}

	// ACTIVE인 글만 찾고, 없으면 POST_NOT_FOUND 예외를 던진다.
	// orElseThrow: Optional이 비어 있으면 괄호 안에서 만든 예외를 던지고, 있으면 꺼낸 값을 돌려준다.
	private Post findActivePost(Long id) {
		return postRepository.findByIdAndStatus(id, PostStatus.ACTIVE)
				.orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));
	}

	// 메모가 비어 있거나 공백뿐이면 null로 저장한다 (화면에서 "메모 없음"을 쉽게 구분하려고)
	private String normalizeMemo(String memo) {
		if (memo == null || memo.isBlank()) {
			return null;
		}
		return memo.strip();
	}
}
