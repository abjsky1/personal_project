package backend.admin.auth;

import org.springframework.data.jpa.repository.JpaRepository;

// 관리자 기기 등록을 읽고 쓰는 Repository (JM-82). 줄은 하나뿐이라 번호 AdminDevice.ID로 찾는다
public interface AdminDeviceRepository extends JpaRepository<AdminDevice, Long> {
}
