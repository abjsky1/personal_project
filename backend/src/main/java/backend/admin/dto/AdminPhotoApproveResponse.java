package backend.admin.dto;

// 운영자 API 사진 올리기(JM-80)의 응답: 어느 글의 사진을 이제 가리지 않는지. 예: {"id": 109, "photoHeld": false}
public record AdminPhotoApproveResponse(Long id, boolean photoHeld) {
}
