package backend.image;

import java.time.Duration;
import java.util.regex.Pattern;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

// 저장한 사진을 브라우저로 보내 준다. GET /images/파일이름
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

	@GetMapping("/images/{key}")
	public ResponseEntity<byte[]> image(@PathVariable String key) {
		if (!KEY_PATTERN.matcher(key).matches()) {
			return ResponseEntity.notFound().build();
		}

		// map: 사진이 있으면 200 응답으로 바꾸고 / orElseGet: 없으면 404
		return imageStorage.load(key)
				.map(bytes -> ResponseEntity.ok()
						.contentType(MediaType.IMAGE_JPEG)
						// 파일 이름이 매번 새로 만든 무작위 이름이라, 같은 이름의 내용이 바뀌는 일이 없다.
						// → 브라우저가 1년 동안 저장해 두고 다시 받지 않게 한다 (서버 부담·휴대폰 데이터 절약)
						.cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
						.body(bytes))
				.orElseGet(() -> ResponseEntity.notFound().build());
	}
}
