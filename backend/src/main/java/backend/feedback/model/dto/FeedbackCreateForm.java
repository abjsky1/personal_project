package backend.feedback.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 피드백 작성 화면의 form에서 보낸 값을 받는 클래스 (PostCreateForm과 같은 방식)
// 글자 수 규칙은 기획서 5절: 제목 100자, 내용 2,000자, 닉네임 20자, 비밀번호 4~20자
public class FeedbackCreateForm {

	@NotBlank(message = "제목을 입력해 주세요.")
	@Size(max = 100, message = "제목은 100자까지 쓸 수 있어요.")
	private String title;

	@NotBlank(message = "내용을 입력해 주세요.")
	@Size(max = 2000, message = "내용은 2,000자까지 쓸 수 있어요.")
	private String content;

	@NotBlank(message = "닉네임을 입력해 주세요.")
	@Size(max = 20, message = "닉네임은 20자까지 쓸 수 있어요.")
	private String nickname;

	@Size(min = 4, max = 20, message = "비밀번호는 4~20자로 입력해 주세요.")
	private String password;

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}

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
}
