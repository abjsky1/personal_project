package backend.notice.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import backend.notice.model.entity.NoticeView;

public interface NoticeViewRepository extends JpaRepository<NoticeView, Long> {

	// 조회수 1 올리기. UPDATE 한 번으로 DB가 더하니까 두 사람이 동시에 열어도 숫자를 잃지 않는다.
	// 바뀐 행 수를 돌려준다: 0이면 아직 한 번도 안 열린 공지라 행이 없다는 뜻 (서비스가 1로 새로 만든다)
	@Modifying(clearAutomatically = true)
	@Query("update NoticeView v set v.viewCount = v.viewCount + 1 where v.noticeId = :id")
	int increment(@Param("id") Long id);
}
