package backend.image;

import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

// 올린 사진을 AWS S3에 저장한다. (배포 서버용)
// S3: AWS의 파일 보관 서비스. 서버(EC2)와 따로 있어서, 서버를 지우거나 새로 만들어도 사진이 남는다.
//
// app.image-storage=s3 일 때만 이 클래스가 쓰인다. (내 PC에서는 LocalImageStorage)
// 버킷(S3의 저장 공간 하나)은 공개하지 않는다. 사진은 브라우저가 S3에서 직접 받지 않고, ImageController를 거쳐서 받는다.
// → 버킷을 외부에 열어 둘 필요가 없고, 사진 주소도 지금처럼 /images/파일이름 으로 그대로 쓴다.
//
// DisposableBean: 서버가 꺼질 때 destroy()를 불러 준다 → S3 연결을 정리한다.
@Slf4j
@Component
@ConditionalOnProperty(name = "app.image-storage", havingValue = "s3")
public class S3ImageStorage implements ImageStorage, DisposableBean {

	// 버킷 안에서 사진을 모아 두는 폴더 이름 (S3에는 진짜 폴더가 없고, 이름 앞부분으로 구분한다)
	private static final String PREFIX = "images/";

	private final S3Client s3;
	private final String bucket;

	// 버킷 이름과 지역(예: ap-northeast-2 = 서울)은 설정에서 받는다.
	public S3ImageStorage(@Value("${app.s3.bucket}") String bucket, @Value("${app.s3.region}") String region) {
		this.bucket = bucket;
		// S3Client: S3와 통신하는 도구
		// 접근 권한(자격 증명)은 코드나 설정 파일에 적지 않는다.
		// EC2 서버에 붙여 둔 IAM 역할(권한 묶음)을 AWS SDK가 자동으로 찾아서 쓴다. → 비밀 키가 어디에도 남지 않는다.
		this.s3 = S3Client.builder().region(Region.of(region)).build();
		log.info("Image storage: S3 bucket={}, region={}", bucket, region);
	}

	@Override
	public String saveJpeg(byte[] jpeg) {
		String key = UUID.randomUUID() + ".jpg";
		// putObject: S3에 파일 하나를 올린다. contentType을 적어 두면 S3에서도 JPEG로 알아본다.
		s3.putObject(
				PutObjectRequest.builder().bucket(bucket).key(PREFIX + key).contentType("image/jpeg").build(),
				RequestBody.fromBytes(jpeg));
		return key;
	}

	@Override
	public Optional<byte[]> load(String key) {
		try {
			// getObjectAsBytes: S3에서 파일 하나를 통째로 받아 온다. (사진 하나가 수백 KB라 메모리로 받아도 괜찮다)
			byte[] bytes = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(PREFIX + key).build())
					.asByteArray();
			return Optional.of(bytes);
		} catch (NoSuchKeyException e) {
			// 그런 파일이 없음 → 404로 응답하게 비어 있는 결과를 돌려준다.
			return Optional.empty();
		}
	}

	@Override
	public void destroy() {
		s3.close();
	}
}
