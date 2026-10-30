package backend.image;

import java.util.Optional;

// 사진 파일을 어디에 저장하고 어디서 꺼낼지를 정하는 "약속"(인터페이스)
// 구현은 두 가지이고, application.properties의 app.image-storage 값으로 하나가 선택된다.
// - LocalImageStorage: 내 PC의 uploads/ 폴더 (개발용, local)
// - S3ImageStorage: AWS S3 (배포 서버용, s3)
// PostService 같은 사용하는 쪽은 이 인터페이스만 알고, 실제로 어디에 저장되는지는 모른다.
// → 저장 장소를 바꿔도 사용하는 코드는 고치지 않아도 된다.
public interface ImageStorage {

	// JPEG 파일 내용을 저장하고, 저장한 파일 이름(key)을 돌려준다. (예: 3f2a9c1e-....jpg)
	String saveJpeg(byte[] jpeg);

	// key에 해당하는 사진 파일 내용을 꺼낸다. 없으면 비어 있는 Optional
	Optional<byte[]> load(String key);
}
