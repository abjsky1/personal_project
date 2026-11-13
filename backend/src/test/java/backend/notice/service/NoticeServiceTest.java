package backend.notice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import backend.notice.model.dto.Notice;
import backend.notice.model.dto.NoticePage;
import backend.notice.model.dto.NoticeSummary;
import backend.notice.model.entity.NoticeView;
import backend.notice.model.repository.NoticeViewRepository;

// 공지 목록의 줄을 만드는 규칙 (JM-76): 번호마다 한 줄, 지운 공지는 번호와 자리만 남긴다.
// 실제 notices.json에는 지운 공지가 없어서, 가짜 목록(mock)으로 번호가 비는 경우를 만든다.
class NoticeServiceTest {

	private final NoticeCatalog catalog = mock(NoticeCatalog.class);
	private final NoticeViewRepository views = mock(NoticeViewRepository.class);
	private final NoticeService service = new NoticeService(catalog, views);

	@Test
	void 지운_공지도_줄을_빼지_않고_번호만_남긴다() {
		// 5번까지 썼고 3번을 지운 상태
		notices(5, 5L, 4L, 2L, 1L);
		when(views.findAllById(List.of(5L, 4L, 3L, 2L, 1L))).thenReturn(List.of(new NoticeView(5L, 7), new NoticeView(3L, 9)));

		NoticePage page = service.getPage(1);

		// 번호가 당겨지지 않는다: 5, 4, 3, 2, 1이 그대로 한 줄씩
		assertThat(page.notices()).extracting(NoticeSummary::id).containsExactly(5L, 4L, 3L, 2L, 1L);
		assertThat(page.notices()).extracting(NoticeSummary::deleted).containsExactly(false, false, true, false, false);
		// 지운 공지의 줄에는 제목도 날짜도 조회수도 없다 (예전에 쌓인 조회수가 DB에 남아 있어도 보여 주지 않는다)
		NoticeSummary gone = page.notices().get(2);
		assertThat(gone.title()).isNull();
		assertThat(gone.date()).isNull();
		assertThat(gone.views()).isZero();
		// 남아 있는 공지는 지금까지와 같다
		assertThat(page.notices().get(0).title()).isEqualTo("공지 5");
		assertThat(page.notices().get(0).date()).isEqualTo("2026.11.13");
		assertThat(page.notices().get(0).views()).isEqualTo(7);
		assertThat(page.notices().get(1).views()).isZero();
		assertThat(page.nav().totalPages()).isEqualTo(1);
	}

	@Test
	void 쪽은_번호를_20개씩_끊고_지운_공지의_자리도_한_줄로_센다() {
		// 45번까지 썼고, 남아 있는 것은 45번과 1번뿐 (나머지는 모두 지운 자리)
		notices(45, 45L, 1L);

		NoticePage first = service.getPage(1);
		assertThat(first.notices()).hasSize(20);
		assertThat(first.notices().get(0).id()).isEqualTo(45);
		assertThat(first.notices().get(19).id()).isEqualTo(26);
		assertThat(first.nav().totalPages()).isEqualTo(3);

		NoticePage last = service.getPage(3);
		assertThat(last.notices()).extracting(NoticeSummary::id).containsExactly(5L, 4L, 3L, 2L, 1L);
		assertThat(last.notices().get(4).deleted()).isFalse();

		// 범위 밖 쪽 번호는 끝 쪽으로 맞춘다
		assertThat(service.getPage(9).nav().page()).isEqualTo(3);
		assertThat(service.getPage(0).nav().page()).isEqualTo(1);
	}

	@Test
	void 공지가_하나도_없으면_줄이_없다() {
		notices(0);

		NoticePage page = service.getPage(1);

		assertThat(page.notices()).isEmpty();
		assertThat(page.nav().page()).isEqualTo(1);
	}

	// 가장 큰 번호가 maxId이고, ids의 번호만 남아 있는 공지 목록을 흉내 낸다
	private void notices(long maxId, Long... ids) {
		when(catalog.maxId()).thenReturn(maxId);
		when(catalog.find(anyLong())).thenReturn(Optional.empty());
		for (Long id : ids) {
			when(catalog.find(id)).thenReturn(Optional.of(
					new Notice(id, "안내", "공지 " + id, LocalDate.of(2026, 11, 13), List.of())));
		}
	}
}
