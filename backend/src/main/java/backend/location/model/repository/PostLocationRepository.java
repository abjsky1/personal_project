// 위치 도메인의 Repository를 두는 패키지
package backend.location.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import backend.location.model.entity.PostLocation;

// 게시글 위치를 DB에서 읽고 쓰는 Repository (기본 키 = 게시글 번호)
// save(), findById(), deleteById() 같은 기본 메서드만 쓰므로 따로 적을 메서드가 없다.
public interface PostLocationRepository extends JpaRepository<PostLocation, Long> {
}
