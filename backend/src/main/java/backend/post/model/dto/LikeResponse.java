package backend.post.model.dto;

// 좋아요를 누른 뒤의 응답 (JM-59). 화면의 숫자를 서버가 센 값으로 바꾸는 데 쓴다. JSON: {"likeCount": 3}
public record LikeResponse(int likeCount) {
}
