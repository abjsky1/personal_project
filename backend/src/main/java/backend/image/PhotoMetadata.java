package backend.image;

import java.time.Instant;

// 사진 원본의 EXIF에서 꺼낸 정보. 사진에 없으면 각각 null이다.
// latitude(위도)·longitude(경도): 사진을 찍은 위치 / takenAt: 촬영 시각
public record PhotoMetadata(Double latitude, Double longitude, Instant takenAt) {

	// 아무 정보도 없을 때 쓰는 값
	public static final PhotoMetadata EMPTY = new PhotoMetadata(null, null, null);

	public boolean hasLocation() {
		return latitude != null && longitude != null;
	}
}
