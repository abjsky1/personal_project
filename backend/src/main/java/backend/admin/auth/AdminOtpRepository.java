package backend.admin.auth;

import org.springframework.data.jpa.repository.JpaRepository;

// 관리자 인증 앱 비밀값을 읽고 쓰는 Repository (JM-82). 줄은 하나뿐이라 번호 AdminOtp.ID로 찾는다
public interface AdminOtpRepository extends JpaRepository<AdminOtp, Long> {
}
