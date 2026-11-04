package backend.comment.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 상세 화면의 댓글 입력 form에서 보낸 값을 받는 클래스 (PostCreateForm과 같은 방식)
// 글자 수 규칙은 기획서 5절: 닉네임 20자, 비밀번호 4~20자, 내용 500자
public class CommentCreateForm {

	@NotBlank(message = "닉네임을 입력해 주세요.")
	@Size(max = 20, message = "닉네임은 20자까지 쓸 수 있어요.")
	private String nickname;

	@Size(min = 4, max = 20, message = "비밀번호는 4~20자로 입력해 주세요.")
	private String password;

	@NotBlank(message = "댓글 내용을 입력해 주세요.")
	@Size(max = 500, message = "댓글은 500자까지 쓸 수 있어요.")
	private String content;

	public String getNickname() {
		return nickname;
	}

	public void setNickname(String nickname) {
		this.nickname = nickname;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}
}
