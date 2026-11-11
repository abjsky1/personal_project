package backend.notice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import backend.notice.model.entity.NoticeView;
import backend.notice.model.repository.NoticeViewRepository;

// 공지 게시판(JM-65): 목록과 상세가 열리고, 사람이 열 때만 조회수가 오르는지 확인한다.
// 로컬 테스트 DB는 개발 서버와 같이 쓰므로 조회수는 절대값 대신 "전보다 1 올랐는지"로 본다
@SpringBootTest
@AutoConfigureMockMvc
class NoticeControllerTest {

	private static final String PHONE_BROWSER = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private NoticeViewRepository noticeViewRepository;

	private long views(long id) {
		return noticeViewRepository.findById(id).map(NoticeView::getViewCount).orElse(0L);
	}

	@Test
	void 공지_목록이_열린다() throws Exception {
		mockMvc.perform(get("/notice"))
				.andExpect(status().isOk())
				.andExpect(view().name("notice/list"))
				.andExpect(model().attributeExists("page"));
	}

	@Test
	void 공지_상세를_열면_조회수가_1_오른다() throws Exception {
		long before = views(1L);

		mockMvc.perform(get("/notice/1").header(HttpHeaders.USER_AGENT, PHONE_BROWSER))
				.andExpect(status().isOk())
				.andExpect(view().name("notice/detail"))
				.andExpect(model().attributeExists("notice"));

		assertThat(views(1L)).isEqualTo(before + 1);
	}

	@Test
	void 봇이_연_것은_조회수에_넣지_않는다() throws Exception {
		long before = views(1L);

		mockMvc.perform(get("/notice/1").header(HttpHeaders.USER_AGENT, "Mozilla/5.0 (compatible; Googlebot/2.1)"))
				.andExpect(status().isOk());

		assertThat(views(1L)).isEqualTo(before);
	}

	@Test
	void 없는_공지는_404() throws Exception {
		mockMvc.perform(get("/notice/99999"))
				.andExpect(status().isNotFound());
	}
}
