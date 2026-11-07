package backend.common.web;

import java.util.List;
import java.util.stream.IntStream;

// 쪽 번호 방식 목록의 "이전 · 1 2 3 · 다음" 재료. 화면은 common/pager.jspf가 이 값으로 그린다. (JM-57)
// JSP에서 ${nav.hasPrev}처럼 꺼내 쓰는데, JSP의 EL은 record의 구성 요소만 읽을 수 있어서
// 계산용 메서드 대신 계산 결과를 미리 값으로 담아 둔다.
public record PageNav(int page, int totalPages, boolean hasPrev, boolean hasNext, List<Integer> pages) {

	// 번호를 한 번에 몇 개까지 보여 줄지 (현재 쪽을 가운데 두고 앞뒤로 2개씩)
	private static final int WINDOW = 5;

	// 현재 쪽 번호와 전체 쪽 수로 화면에 필요한 값을 모두 계산한다
	public static PageNav of(int page, int totalPages) {
		List<Integer> pages;
		if (totalPages < 1) {
			// 글이 하나도 없으면 번호 줄을 그리지 않는다
			pages = List.of();
		} else {
			// 전체가 5쪽 이하면 전부, 많으면 현재 쪽 앞뒤로 2개씩. 끝에 가까우면 한쪽으로 몰아서 5개를 유지한다
			int start = Math.max(1, page - WINDOW / 2);
			int end = Math.min(totalPages, start + WINDOW - 1);
			start = Math.max(1, end - WINDOW + 1);
			pages = IntStream.rangeClosed(start, end).boxed().toList();
		}
		return new PageNav(page, totalPages, page > 1, page < totalPages, pages);
	}
}
