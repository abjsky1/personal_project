package backend.post.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import backend.post.model.entity.AppPostView;

// 앱 조회 기록 (JM-90). 지금은 쓰기만 한다. 세는 쿼리는 운영자 통계에 앱 숫자를 넣을 때 만든다
public interface AppPostViewRepository extends JpaRepository<AppPostView, Long> {
}
