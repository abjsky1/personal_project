package backend.notice.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import backend.notice.model.dto.Notice;
import backend.notice.model.dto.NoticeBlock;
import backend.notice.model.dto.NoticeSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

// 공지 글(src/main/resources/notices.json)을 서버가 켜질 때 한 번 읽어 둔다 (JM-65).
// 공지 글은 DB가 아니라 이 파일로 git에서 관리한다 → 바뀐 기능과 같은 PR에서 공지를 더하고, 언제 무엇을 썼는지 기록이 남는다.
// 파일이 잘못되면(번호 겹침, 빈 제목, 날짜 형식) 서버가 켜지지 않게 해서 테스트에서 바로 드러나게 한다.
@Component
public class NoticeCatalog {

	private static final String SOURCE = "notices.json";

	// 번호가 큰 것(최신)부터
	private final List<Notice> notices;

	public NoticeCatalog(JsonMapper jsonMapper) {
		List<NoticeSource> sources;
		try (InputStream in = new ClassPathResource(SOURCE).getInputStream()) {
			sources = jsonMapper.readValue(in, new TypeReference<List<NoticeSource>>() {
			});
		} catch (IOException e) {
			throw new UncheckedIOException(SOURCE + "를 읽지 못했습니다", e);
		}
		Set<Long> ids = new HashSet<>();
		List<Notice> list = new ArrayList<>();
		for (NoticeSource source : sources) {
			if (source.id() <= 0 || !ids.add(source.id())) {
				throw new IllegalStateException(SOURCE + ": 공지 번호가 없거나 겹칩니다: " + source.id());
			}
			if (isBlank(source.category()) || isBlank(source.title()) || isBlank(source.date())
					|| source.body() == null || source.body().isEmpty()) {
				throw new IllegalStateException(SOURCE + ": " + source.id() + "번 공지에 분류, 제목, 날짜, 본문이 다 있어야 합니다");
			}
			list.add(new Notice(source.id(), source.category(), source.title(), LocalDate.parse(source.date()),
					toBlocks(source.body())));
		}
		list.sort(Comparator.comparingLong(Notice::id).reversed());
		this.notices = List.copyOf(list);
	}

	public List<Notice> all() {
		return notices;
	}

	public Optional<Notice> find(long id) {
		return notices.stream().filter(notice -> notice.id() == id).findFirst();
	}

	// 본문 줄들을 덩어리로: "- "로 시작하는 줄이 이어지면 점 목록 하나, 나머지 줄은 문단 하나씩
	static List<NoticeBlock> toBlocks(List<String> lines) {
		List<NoticeBlock> blocks = new ArrayList<>();
		List<String> items = new ArrayList<>();
		for (String line : lines) {
			if (line.startsWith("- ")) {
				items.add(line.substring(2).trim());
				continue;
			}
			if (!items.isEmpty()) {
				blocks.add(new NoticeBlock("", List.copyOf(items)));
				items.clear();
			}
			blocks.add(new NoticeBlock(line.trim(), List.of()));
		}
		if (!items.isEmpty()) {
			blocks.add(new NoticeBlock("", List.copyOf(items)));
		}
		return List.copyOf(blocks);
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
