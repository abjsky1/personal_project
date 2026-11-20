package backend.visit.web;

import java.io.Serial;
import java.io.Serializable;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;

// 세션에 두는 이 방문의 번호와 지금까지 남긴 줄 수 (사용자 흐름, JM-92). 세션이 끝나면(30분 동안 쓰지 않으면) 함께 사라진다.
// 번호는 무작위라 세션 번호(JSESSIONID)와도, 같은 브라우저의 다른 방문과도 이어지지 않는다.
// 세션 번호를 DB에 두지 않는 이유: 그 값이 새면 남의 세션을 빌려 쓸 수 있다
final class PageFlowVisit implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	private static final SecureRandom RANDOM = new SecureRandom();

	private final long key;

	// AtomicInteger: 같은 세션의 요청 두 개(탭 두 개 등)가 동시에 끝나도 번호가 겹치지 않는다
	private final AtomicInteger lines = new AtomicInteger();

	// 관리자로 들어온 세션이면 true. 그 뒤로는 로그아웃해도 이 세션이 끝날 때까지 남기지 않는다
	private volatile boolean operator;

	private PageFlowVisit(long key) {
		this.key = key;
	}

	// 맨 앞 비트를 지워 0 이상의 수만 쓴다 (2의 63제곱 가지라 방문끼리 겹칠 일이 없다)
	static PageFlowVisit start() {
		return new PageFlowVisit(RANDOM.nextLong() & Long.MAX_VALUE);
	}

	long key() {
		return key;
	}

	// 다음 줄의 순서 (1부터)
	int nextSeq() {
		return lines.incrementAndGet();
	}

	void markOperator() {
		operator = true;
	}

	boolean operator() {
		return operator;
	}
}
