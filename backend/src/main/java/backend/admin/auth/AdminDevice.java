// 관리자 화면에 들어오는 사람을 확인하는 코드를 두는 패키지 (JM-82)
package backend.admin.auth;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// 관리자 화면에 들어올 수 있는 기기 하나 (JM-82). DB의 admin_device 테이블과 짝을 이루고, 줄은 언제나 하나(번호 1)뿐이다.
// 운영자의 노트북 브라우저를 처음 한 번 등록하면 생긴다. 그 브라우저에는 길고 무작위인 기기 쿠키가 심기고,
// DB에는 그 쿠키 값의 해시만 둔다 (DB가 새어도 쿠키 값은 알 수 없다).
// 기기를 바꾸려면 이 줄을 지우고 새 기기에서 다시 등록한다 (기획서 16절).
@Entity
@Table(name = "admin_device")
public class AdminDevice {

	public static final long ID = 1L;

	@Id
	private Long id;

	// 기기 쿠키 값의 SHA-256 해시 (16진수 64글자)
	@Column(nullable = false, length = 64)
	private String tokenHash;

	// 등록한 기기를 사람이 알아보기 위한 이름 (예: "Windows, Chrome"). 브라우저가 보낸 정보로 만든다
	@Column(nullable = false, length = 100)
	private String label;

	@Column(nullable = false)
	private Instant createdAt;

	// 마지막으로 이 기기에서 들어온 때
	private Instant lastUsedAt;

	protected AdminDevice() {
	}

	public AdminDevice(String tokenHash, String label) {
		this.id = ID;
		this.tokenHash = tokenHash;
		this.label = label;
		this.createdAt = Instant.now();
		this.lastUsedAt = this.createdAt;
	}

	public void markUsed() {
		this.lastUsedAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public String getTokenHash() {
		return tokenHash;
	}

	public String getLabel() {
		return label;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getLastUsedAt() {
		return lastUsedAt;
	}
}
