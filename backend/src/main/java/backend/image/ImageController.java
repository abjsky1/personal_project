package backend.image;

import java.time.Duration;
import java.util.regex.Pattern;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import backend.post.service.PhotoVisibility;
import lombok.RequiredArgsConstructor;

// 저장한 사진을 브라우저로 보내 준다. 주소는 둘이다 (JM-95)
// - GET /images/파일이름: 사이트에 보이는 글의 사진만 보낸다 (PhotoVisibility). 지운 글과 가린 글의 사진, 어느 글의 사진도 아닌 파일은 404
// - GET /admin/images/파일이름: 관리자 화면용. 지운 글과 가린 글의 사진도 보낸다.
//   로그인 검사는 /admin 아래 주소 전부에 거는 AdminSessionInterceptor가 한다 (AdminConfig). 로그인하지 않았으면 로그인 화면으로 보낸다
// 사진이 내 PC 폴더에 있든(local) S3에 있든(s3) ImageStorage가 알아서 꺼내 주므로, 주소는 똑같다.
//
// @RestController: 돌려준 값을 화면(JSP)이 아니라 응답 본문 그대로 보낸다. (여기서는 사진 파일 내용)
@RestController
@RequiredArgsConstructor
public class ImageController {

	// 파일 이름 모양: 우리가 만든 UUID(8-4-4-4-12자리 16진수) + .jpg
	// 이 모양이 아니면 아예 찾지 않는다 → "../application.properties" 같은 이름으로 다른 파일을 꺼내 가는 공격을 막는다.
	private static final Pattern KEY_PATTERN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg");

	private final ImageStorage imageStorage;
	private final PhotoVisibility photoVisibility;

	@GetMapping("/images/{key}")
	public ResponseEntity<byte[]> image(@PathVariable String key) {
		if (!KEY_PATTERN.matcher(key).matches() || !photoVisibility.isPublic(key)) {
			return notFound();
		}
		// 파일 이름이 매번 새로 만든 무작위 이름이라, 같은 이름의 내용이 바뀌는 일이 없다.
		// → 브라우저가 1년 동안 저장해 두고 다시 받지 않게 한다 (서버 부담, 휴대폰 데이터 절약)
		// 글을 지우거나 사진을 가려도 이미 받은 브라우저에는 남는다. 그 사람은 이미 본 사진이라 그대로 둔다 (JM-95에서 정함)
		return send(key, CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
	}

	// 관리자 화면의 사진은 브라우저에 남기지 않는다 (no-store): 운영자 브라우저의 디스크에 지운 사진이나 가린 사진이 쌓이지 않게.
	// private: 중간의 공유 캐시(프록시)도 저장하지 않는다
	@GetMapping("/admin/images/{key}")
	public ResponseEntity<byte[]> adminImage(@PathVariable String key) {
		if (!KEY_PATTERN.matcher(key).matches()) {
			return notFound();
		}
		return send(key, CacheControl.noStore().cachePrivate());
	}

	// map: 사진이 있으면 200 응답으로 바꾸고 / orElseGet: 없으면 404
	private ResponseEntity<byte[]> send(String key, CacheControl cacheControl) {
		return imageStorage.load(key)
				.map(bytes -> ResponseEntity.ok()
						.contentType(MediaType.IMAGE_JPEG)
						.cacheControl(cacheControl)
						.body(bytes))
				.orElseGet(ImageController::notFound);
	}

	// 404도 브라우저가 기억하지 않게 한다 (no-store): 가린 사진을 운영자가 올리면 같은 주소가 바로 200이어야 한다
	private static ResponseEntity<byte[]> notFound() {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore()).build();
	}
}
