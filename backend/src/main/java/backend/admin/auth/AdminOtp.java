package backend.admin.auth;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// 관리자 휴대폰 인증 앱의 비밀값 (JM-82). DB의 admin_otp 테이블과 짝을 이루고, 줄은 언제나 하나(번호 1)뿐이다.
// 관리자 화면에 처음 들어올 때(비밀번호가 맞은 뒤) 인증 앱을 등록하면 생긴다. 그 뒤로는 이 휴대폰의 6자리 숫자가 있어야 들어온다.
// 휴대폰을 잃어버리면 이 줄을 지우고 다시 등록한다 (기획서 16절).
@Entity
@Table(name = "admin_otp")
public class AdminOtp {

	public static final long ID = 1L;

	@Id
	private Long id;

	// 인증 앱과 나눠 가진 비밀값 (Base32 글자)
	@Column(nullable = false, length = 64)
	private String secret;

	// 마지막으로 받아 준 30초 칸 번호. 같은 숫자를 두 번 쓰지 못하게 한다
	// (어깨너머로 본 숫자를 30초 안에 다시 넣어 들어오는 것을 막는다)
	private Long lastUsedStep;

	@Column(nullable = false)
	private Instant createdAt;

	protected AdminOtp() {
	}

	public AdminOtp(String secret, long usedStep) {
		this.id = ID;
		this.secret = secret;
		this.lastUsedStep = usedStep;
		this.createdAt = Instant.now();
	}

	// 이 칸 번호를 쓴 것으로 남긴다. 이미 쓴 칸이거나 그보다 앞선 칸이면 false (받아 주지 않는다)
	public boolean useStep(long step) {
		if (lastUsedStep != null && step <= lastUsedStep) {
			return false;
		}
		lastUsedStep = step;
		return true;
	}

	public Long getId() {
		return id;
	}

	public String getSecret() {
		return secret;
	}

	public Long getLastUsedStep() {
		return lastUsedStep;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
