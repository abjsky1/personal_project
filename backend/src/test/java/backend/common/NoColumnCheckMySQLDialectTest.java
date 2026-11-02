package backend.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@SpringBootTest
class NoColumnCheckMySQLDialectTest {

	@PersistenceContext
	private EntityManager entityManager;

	// CHECK 제약이 있으면 enum에 상태를 추가했을 때 그 값의 저장이 실패한다. 라이브러리를 올리다 설정이 풀리면 여기서 알 수 있다.
	// (CI는 빈 DB에서 테이블을 새로 만드므로, JPA가 제약을 만들지 않는다는 것을 그대로 확인한다)
	@Test
	void DB에_CHECK_제약이_없다() {
		Number count = (Number) entityManager.createNativeQuery("""
				select count(*) from information_schema.table_constraints
				where table_schema = database() and constraint_type = 'CHECK'
				""").getSingleResult();

		assertThat(count.intValue()).isZero();
	}
}
