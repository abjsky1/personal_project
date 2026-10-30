package backend.image;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// 설정 하나(app.image-storage=s3)로 사진 저장소가 S3로 바뀌는지 확인하는 테스트
// 실제 S3에 접속하지는 않는다. (S3 연결 도구는 사진을 저장·조회할 때 처음 접속하므로, 서버가 켜지는 것까지만 확인)
// 실제 S3 저장·조회는 배포 서버에서 IAM 역할과 버킷을 만든 뒤 확인한다.
@SpringBootTest(properties = {
		"app.image-storage=s3",
		"app.s3.bucket=test-bucket" })
class S3ImageStorageSwitchTest {

	@Autowired
	private ImageStorage imageStorage;

	@Test
	void app_image_storage가_s3면_S3_저장소를_쓴다() {
		assertThat(imageStorage).isInstanceOf(S3ImageStorage.class);
	}
}
