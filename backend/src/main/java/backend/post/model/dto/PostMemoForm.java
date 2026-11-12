package backend.post.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 글 상세의 "메모 수정" 칸에서 보낸 값을 받는 클래스 (JM-53, 지인 피드백 7번)
// 고칠 수 있는 것은 메모뿐이라 칸이 둘이다: 새 메모와, 글을 올릴 때 넣은 비밀번호.
// 검사 규칙을 적는 방식은 글쓰기의 PostCreateForm과 같다. 걸리면 상세 화면으로 돌아가 메모 수정 칸 아래에 메시지를 보여 준다.
public class PostMemoForm {

	// 글을 올릴 때와 같은 규칙(300자). 비워서 보내면 메모를 지운다.
	@Size(max = 300, message = "메모는 300자까지 쓸 수 있어요.")
	private String memo;

	// 여기서는 비어 있지 않은지만 본다. 맞는 비밀번호인지는 Service가 저장된 해시와 비교해서 확인한다.
	@NotBlank(message = "비밀번호를 입력해 주세요.")
	private String password;

	public String getMemo() {
		return memo;
	}

	public void setMemo(String memo) {
		this.memo = memo;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}
}
