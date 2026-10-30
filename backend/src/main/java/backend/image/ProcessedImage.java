package backend.image;

// 사진 처리 결과
// main: 저장할 큰 사진, thumbnail: 목록용 썸네일 (둘 다 EXIF를 지운 JPEG 파일 내용)
// metadata: EXIF를 지우기 전 원본에서 꺼낸 위치·촬영 시각 (저장 여부는 사용자 동의에 따라 PostService가 정한다)
public record ProcessedImage(byte[] main, byte[] thumbnail, PhotoMetadata metadata) {
}
