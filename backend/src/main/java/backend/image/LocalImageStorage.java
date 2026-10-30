// 사진 저장·처리와 관련된 코드를 두는 패키지
package backend.image;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// 올린 사진을 내 PC의 폴더(uploads/)에 저장한다. (개발용)
// 배포 서버에서는 서버를 새로 만들면 폴더 내용이 사라지므로 S3ImageStorage를 쓴다.
//
// @ConditionalOnProperty: 설정 값에 따라 이 클래스를 쓸지 정한다.
// app.image-storage=local 이거나, 설정이 아예 없으면(matchIfMissing) 이 클래스가 ImageStorage로 쓰인다.
@Component
@ConditionalOnProperty(name = "app.image-storage", havingValue = "local", matchIfMissing = true)
public class LocalImageStorage implements ImageStorage {

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

	// ImageProcessor가 만든 JPEG 파일 내용을 저장하고, 저장한 파일 이름(key)을 돌려준다.
	@Override
	public String saveJpeg(byte[] jpeg) {
		// 파일 이름은 사용자가 올린 이름을 쓰지 않고 무작위 이름(UUID)으로 새로 만든다.
		// → 이름이 겹쳐 덮어쓰거나, "../" 같은 이름으로 다른 폴더에 저장되는 문제를 막는다.
		String key = UUID.randomUUID() + ".jpg";

		try {
			// Files.write: 바이트 배열을 파일로 쓴다.
			Files.write(rootDir.resolve(key), jpeg);
		} catch (IOException e) {
			// 파일 저장 실패는 서버 문제다. 확인 예외(IOException)를 실행 중 예외로 바꿔 던지면
			// PageExceptionHandler가 500 에러 화면을 보여 주고 원인을 로그에 남긴다.
			throw new UncheckedIOException("사진 파일 저장 실패: " + key, e);
		}
		return key;
	}

	// 폴더에서 사진 파일을 읽는다. 파일이 없으면 비어 있는 Optional
	// (key 모양 검사는 ImageController가 먼저 해서, 여기에는 "uuid.jpg" 모양만 들어온다)
	@Override
	public Optional<byte[]> load(String key) {
		Path file = rootDir.resolve(key);
		if (!Files.exists(file)) {
			return Optional.empty();
		}
		try {
			return Optional.of(Files.readAllBytes(file));
		} catch (IOException e) {
			throw new UncheckedIOException("사진 파일 읽기 실패: " + key, e);
		}
	}
}
