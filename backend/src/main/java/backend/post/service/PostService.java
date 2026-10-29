package backend.post.service;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import backend.common.error.BusinessException;
import backend.common.error.ErrorCode;
import backend.image.LocalImageStorage;
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
// → Spring이 그 생성자로 아래 세 객체를 넣어 준다 (생성자 주입). 코드에 생성자가 안 보여도 실제로는 있다.
// @Slf4j(Lombok): 로그를 찍는 log 필드를 자동으로 만들어 준다. (LoggerFactory.getLogger(PostService.class)와 같다)
@Slf4j
@Service
@RequiredArgsConstructor
public class PostService {

	// 목록 한 번에 보여 줄 글 수
	private static final int PAGE_SIZE = 20;

	private final PostRepository postRepository;
	private final LocalImageStorage imageStorage;
	private final PasswordEncoder passwordEncoder;

	// 글 저장. 저장된 글 번호를 돌려준다.
	// @Transactional: 이 메서드 안의 DB 작업을 하나로 묶는다. 중간에 예외가 나면 DB 변경을 모두 되돌린다.
	@Transactional
	public Long create(PostCreateForm form) {
		// 사진 파일을 먼저 저장하고, 파일 이름(key)을 받는다.
		// (DB 저장이 실패하면 파일만 남을 수 있지만, 쓰이지 않는 파일일 뿐이라 MVP에서는 그대로 둔다)
		String imageKey = imageStorage.save(form.getImage());

		// encode(): 비밀번호를 BCrypt 해시로 바꾼다. 같은 비밀번호도 매번 다른 해시가 나온다(salt).
		// 나중에 삭제할 때는 matches(입력한 비밀번호, 저장된 해시)로 맞는지만 확인한다.
		Post post = new Post(
				form.getNickname().strip(),
				passwordEncoder.encode(form.getPassword()),
				normalizeMemo(form.getMemo()),
				imageKey);

		return postRepository.save(post).getId();
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
