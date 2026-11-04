package backend.admin.dto;

import backend.post.model.entity.PostStatus;

// 운영자 삭제 API의 응답: 무엇을 어떤 상태로 바꿨는지. 예: {"id": 12, "status": "DELETED_BY_ADMIN"}
public record AdminDeleteResponse(Long id, PostStatus status) {
}
