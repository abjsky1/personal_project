package backend.post.model.dto;

import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 글쓰기 화면의 form에서 보낸 값을 받는 클래스
// form의 input name과 필드 이름이 같으면 Spring이 값을 자동으로 채운다. (name="nickname" → nickname 필드)
//
// 필드 위의 @NotBlank, @Size: 값이 규칙에 맞는지 검사하는 규칙 (Bean Validation)
// Controller에서 @Valid를 붙이면 검사가 실행되고, 어긋나면 message의 문장이 화면에 표시된다.
public class PostCreateForm {

	// @NotBlank: null, 빈 문자열, 공백만 있는 값을 거절한다.
	@NotBlank(message = "닉네임을 입력해 주세요.")
	@Size(max = 20, message = "닉네임은 20자까지 쓸 수 있어요.")
	private String nickname;

	// @Size(min, max): 글자 수 범위. 빈 값("")도 4자 미만이라 여기서 걸린다.
	@Size(min = 4, max = 20, message = "비밀번호는 4~20자로 입력해 주세요.")
	private String password;

	@Size(max = 300, message = "메모는 300자까지 쓸 수 있어요.")
	private String memo;

	// MultipartFile: form으로 올린 파일. 파일이 있는지, 형식이 맞는지는 Controller에서 직접 검사한다.
	private MultipartFile image;

	// Spring이 form 값을 채울 때 setter를, JSP가 값을 다시 보여 줄 때 getter를 쓴다.
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

	public String getMemo() {
		return memo;
	}

	public void setMemo(String memo) {
		this.memo = memo;
	}

	public MultipartFile getImage() {
		return image;
	}

	public void setImage(MultipartFile image) {
		this.image = image;
	}
}
