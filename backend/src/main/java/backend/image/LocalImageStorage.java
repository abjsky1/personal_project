// 사진 저장·처리와 관련된 코드를 두는 패키지
package backend.image;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

// 올린 사진을 내 PC의 폴더(uploads/)에 저장한다. (개발용)
// 배포(S4)에서는 서버를 다시 올리면 폴더 내용이 사라지므로, 이 클래스 대신 S3에 저장하는 클래스로 바꾼다.
@Component
public class LocalImageStorage {

	// 사진을 저장할 폴더의 전체 경로 (예: C:/personal_project/uploads)
	// 주의: 자바 코드에는 주석이라도 역슬래시 뒤에 u를 쓰면 안 된다. 자바가 이를 유니코드 문자 코드의 시작으로 읽어서 컴파일 에러가 난다.
	private final Path rootDir;

	// @Value("${설정 이름}"): application.properties의 값을 꺼내 넣어 준다 → app.upload-dir=../uploads
	public LocalImageStorage(@Value("${app.upload-dir}") String uploadDir) throws IOException {
		// toAbsolutePath(): "../uploads" 같은 상대 경로를 전체 경로로 바꾼다 / normalize(): ".." 같은 부분을 정리한다
		this.rootDir = Path.of(uploadDir).toAbsolutePath().normalize();
		// 폴더가 없으면 만든다 (이미 있으면 아무 일도 하지 않는다)
		Files.createDirectories(rootDir);
	}

	// 받을 수 있는 사진 형식인지 확인한다. 지금은 JPEG와 PNG만 받는다.
	// contentType은 브라우저가 알려 주는 값이라 속일 수 있다 → 3번 조각에서 파일 내용(매직 바이트)으로 다시 검사한다.
	public static boolean isSupported(String contentType) {
		return "image/jpeg".equals(contentType) || "image/png".equals(contentType);
	}

	// 사진 파일을 저장하고, 저장한 파일 이름(key)을 돌려준다.
	public String save(MultipartFile file) {
		// 파일 이름은 사용자가 올린 이름을 쓰지 않고 무작위 이름(UUID)으로 새로 만든다.
		// → 이름이 겹쳐 덮어쓰거나, "../" 같은 이름으로 다른 폴더에 저장되는 문제를 막는다.
		String extension = "image/png".equals(file.getContentType()) ? ".png" : ".jpg";
		String key = UUID.randomUUID() + extension;

		try {
			// transferTo: 업로드된 파일을 지정한 위치에 저장한다.
			file.transferTo(rootDir.resolve(key));
		} catch (IOException e) {
			// 파일 저장 실패는 서버 문제다. 확인 예외(IOException)를 실행 중 예외로 바꿔 던지면
			// GlobalExceptionHandler가 500으로 처리하고 원인을 로그에 남긴다.
			throw new UncheckedIOException("사진 파일 저장 실패: " + key, e);
		}
		return key;
	}

	public Path getRootDir() {
		return rootDir;
	}
}
