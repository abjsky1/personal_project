package backend.common;

import org.hibernate.dialect.MySQLDialect;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;

// JPA(Hibernate)가 테이블을 만들 때 컬럼에 CHECK 제약을 붙이지 않게 한다.
// (application.properties의 spring.jpa.database-platform으로 이 클래스를 쓰게 한다)
//
// Hibernate는 enum 컬럼에 check (status in ('PENDING', 'DONE', ...))를 자동으로 붙이는데,
// ddl-auto=update는 이미 만든 제약을 고치지 않는다.
// → enum에 상태를 하나 추가하면 그 값을 저장할 때 DB가 거절한다. 뒤에서 도는 분석에서는 "분석 중"에 조용히 멈춘다.
// 허용 값은 Java의 enum이 이미 지키고 있어서 DB 제약이 없어도 된다. 이걸 끄는 설정이 따로 없어서 이렇게 끈다.
// (docs/troubleshooting/2026-11-02-enum-check-constraint.md)
public class NoColumnCheckMySQLDialect extends MySQLDialect {

	// Hibernate가 DB에 접속해 알아낸 정보(MySQL 버전 등)를 이 생성자로 넘겨준다.
	public NoColumnCheckMySQLDialect(DialectResolutionInfo info) {
		super(info);
	}

	@Override
	public boolean supportsColumnCheck() {
		return false;
	}
}
