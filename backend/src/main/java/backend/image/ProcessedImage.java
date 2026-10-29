package backend.image;

// 사진 처리 결과: 저장할 큰 사진과 목록용 썸네일 (둘 다 JPEG 파일 내용)
public record ProcessedImage(byte[] main, byte[] thumbnail) {
}
