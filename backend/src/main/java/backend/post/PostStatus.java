// 게시글 기능을 모아 두는 패키지
package backend.post;

// 게시글 상태. 삭제해도 DB에서 지우지 않고 상태만 바꾼다. (기획서 5.1절)
// 목록·상세 화면에는 ACTIVE인 글만 보여 준다.
public enum PostStatus {
	// 정상 노출 (글을 쓰면 이 상태로 시작)
	ACTIVE,
	// 작성자가 비밀번호를 확인하고 삭제함
	DELETED_BY_AUTHOR,
	// 운영자가 삭제함
	DELETED_BY_ADMIN
}
