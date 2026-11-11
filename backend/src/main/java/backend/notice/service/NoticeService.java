package backend.notice.service;

import java.time.format.DateTimeFormatter;
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

	// 목록: 최신순 20개씩. 범위 밖 쪽 번호는 끝 쪽으로 맞춘다 (피드백 목록과 같은 방식)
	@Transactional(readOnly = true)
	public NoticePage getPage(int requestedPage) {
		List<Notice> all = catalog.all();
		int totalPages = (all.size() + PAGE_SIZE - 1) / PAGE_SIZE;
		int page = Math.max(1, Math.min(requestedPage, Math.max(totalPages, 1)));
		int from = Math.min((page - 1) * PAGE_SIZE, all.size());
		List<Notice> slice = all.subList(from, Math.min(from + PAGE_SIZE, all.size()));
		Map<Long, Long> views = noticeViewRepository.findAllById(slice.stream().map(Notice::id).toList()).stream()
				.collect(Collectors.toMap(NoticeView::getNoticeId, NoticeView::getViewCount));
		List<NoticeSummary> rows = slice.stream()
				.map(notice -> new NoticeSummary(notice.id(), notice.category(), notice.title(),
						notice.date().format(DATE), views.getOrDefault(notice.id(), 0L)))
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
