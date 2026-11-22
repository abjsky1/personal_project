package backend.visit.web;

import java.io.Serial;
import java.io.Serializable;
import java.security.SecureRandom;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import backend.visit.model.entity.Screen;

// 세션에 두는 이 방문의 번호, 지금까지 남긴 줄 수, 이 방문이 쓰거나 고친 글 (사용자 흐름, JM-92).
// 세션이 끝나면(30분 동안 쓰지 않으면) 함께 사라진다.
// 번호는 무작위라 세션 번호(JSESSIONID)와도, 같은 브라우저의 다른 방문과도 이어지지 않는다.
// 세션 번호를 DB에 두지 않는 이유: 그 값이 새면 남의 세션을 빌려 쓸 수 있다
final class PageFlowVisit implements Serializable {

	@Serial
	private static final long serialVersionUID = 2L;

	private static final SecureRandom RANDOM = new SecureRandom();

	// 한 방문에서 기억하는 "쓰거나 고친 글"의 최대 개수 (세션 메모리를 끝없이 쓰지 않게)
	private static final int MAX_ACTED = 100;

	private final long key;

	// AtomicInteger: 같은 세션의 요청 두 개(탭 두 개 등)가 동시에 끝나도 번호가 겹치지 않는다
	private final AtomicInteger lines = new AtomicInteger();

	// 이 방문이 올리거나 쓰거나 고친 글, 댓글을 단 글 ("POST:8", "FEEDBACK:5"). 이 글들의 번호는 이 방문의 줄에 남기지 않는다.
	// 그래야 흐름을 글쓴이와 번호로 잇지 못한다 (올리기 뒤에 넘어가는 새 글 화면이 그 번호를 남기던 것을 검토에서 찾았다)
	private final Set<String> acted = ConcurrentHashMap.newKeySet();

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

	void rememberActed(Screen.Match match) {
		if (acted.size() < MAX_ACTED) {
			acted.add(keyOf(match));
		}
	}

	boolean acted(Screen.Match match) {
		return match.id() != null && acted.contains(keyOf(match));
	}

	private static String keyOf(Screen.Match match) {
		return match.screen().name() + ":" + match.id();
	}
}
