package backend.notice.service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import backend.common.error.BusinessException;
import backend.common.error.ErrorCode;
import backend.common.web.PageNav;
import backend.common.web.Visitors;
import backend.notice.model.dto.Notice;
import backend.notice.model.dto.NoticeDetail;
import backend.notice.model.dto.NoticePage;
import backend.notice.model.dto.NoticeSummary;
import backend.notice.model.entity.NoticeView;
import backend.notice.model.repository.NoticeViewRepository;
import lombok.RequiredArgsConstructor;

// 공지 목록과 상세 (JM-65). 글은 NoticeCatalog(notices.json), 조회수는 DB(notice_views)
@Service
@RequiredArgsConstructor
public class NoticeService {

	private static final int PAGE_SIZE = 20;
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");

	private final NoticeCatalog catalog;
	private final NoticeViewRepository noticeViewRepository;

	// 목록: 번호가 큰 것(최신)부터 20개씩. 범위 밖 쪽 번호는 끝 쪽으로 맞춘다 (피드백 목록과 같은 방식)
	//
	// 줄은 "있는 공지"가 아니라 "번호"를 기준으로 만든다 (JM-76): 가장 큰 번호부터 1번까지, 번호마다 한 줄.
	// 그 번호의 공지가 notices.json에 없으면(지운 공지) 줄을 빼지 않고 "삭제된 공지" 줄을 넣는다.
	// → 공지를 지워도 다른 공지의 번호와 자리가 당겨지지 않는다. 공지를 지우는 방법은 notices.json에서 그 항목을 지우는 것이다
	@Transactional(readOnly = true)
	public NoticePage getPage(int requestedPage) {
		long maxId = catalog.maxId();
		int totalPages = (int) ((maxId + PAGE_SIZE - 1) / PAGE_SIZE);
		int page = Math.max(1, Math.min(requestedPage, Math.max(totalPages, 1)));
		// 이 쪽에 들어갈 번호들: 1쪽이면 maxId부터 20개, 2쪽이면 그다음 20개. 1번보다 아래로는 내려가지 않는다
		long first = maxId - (long) (page - 1) * PAGE_SIZE;
		List<Long> numbers = new ArrayList<>();
		for (long number = first; number >= 1 && numbers.size() < PAGE_SIZE; number--) {
			numbers.add(number);
		}
		Map<Long, Long> views = noticeViewRepository.findAllById(numbers).stream()
				.collect(Collectors.toMap(NoticeView::getNoticeId, NoticeView::getViewCount));
		List<NoticeSummary> rows = numbers.stream()
				.map(number -> catalog.find(number)
						.map(notice -> new NoticeSummary(notice.id(), notice.category(), notice.title(),
								notice.date().format(DATE), views.getOrDefault(notice.id(), 0L), false))
						.orElseGet(() -> NoticeSummary.deleted(number)))
				.toList();
		return new NoticePage(rows, PageNav.of(page, totalPages));
	}

	// 상세: 사람이 열었으면 조회수를 1 올리고 보여 준다. 없는 번호면 NOTICE_NOT_FOUND (404 화면)
	// 사람인지는 User-Agent로 가린다 (검색 엔진, 미리보기, 캡처 도구 제외: Visitors). 같은 사람이 다시 열어도 센다
	@Transactional
	public NoticeDetail getDetail(long id, String userAgent) {
		Notice notice = catalog.find(id).orElseThrow(() -> new BusinessException(ErrorCode.NOTICE_NOT_FOUND));
		if (Visitors.isPerson(userAgent) && noticeViewRepository.increment(id) == 0) {
			// 처음 열린 공지: 행이 없어서 UPDATE가 0건 → 1로 새로 만든다
			noticeViewRepository.save(new NoticeView(id, 1));
		}
		long views = noticeViewRepository.findById(id).map(NoticeView::getViewCount).orElse(0L);
		return new NoticeDetail(notice.id(), notice.category(), notice.title(), notice.date().format(DATE), views,
				notice.blocks());
	}
}
