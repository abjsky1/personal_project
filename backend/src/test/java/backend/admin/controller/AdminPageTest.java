package backend.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import backend.admin.auth.AdminAuthService;
import backend.admin.auth.AdminDevice;
import backend.admin.auth.AdminDeviceRepository;
import backend.post.model.dto.FeedPage;
import backend.post.model.dto.PostSummary;
import backend.post.model.entity.Post;
import backend.post.model.entity.PostStatus;
import backend.post.model.repository.PostRepository;
import jakarta.servlet.http.Cookie;

// 관리자 화면(/admin)의 기기 등록, 로그인(등록한 브라우저와 비밀번호), 글 목록과 삭제를 확인하는 테스트 (JM-82)
// 틀린 횟수는 IP마다 세므로, 테스트끼리 섞이지 않게 테스트마다 다른 IP를 쓴다
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.admin.password=test-admin-password")
class AdminPageTest {

	private static final String PASSWORD = "test-admin-password";
	private static final String LAPTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0 Safari/537.36";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AdminDeviceRepository deviceRepository;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	// 개발 DB에 등록해 둔 기기가 있어도 테스트마다 비우고 시작한다 (테스트가 끝나면 되돌아간다)
	@BeforeEach
	void clearDevice() {
		deviceRepository.deleteAllInBatch();
	}

	@Test
	void 로그인하지_않으면_관리자_화면에서_로그인_화면으로_보낸다() throws Exception {
		mockMvc.perform(get("/admin/posts"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/admin/login"));
	}

	@Test
	void 처음에는_비밀번호가_맞으면_이_브라우저를_관리자_기기로_등록하고_들어간다() throws Exception {
		MockHttpSession session = new MockHttpSession();

		mockMvc.perform(post("/admin/login").session(session).param("password", PASSWORD).with(from("10.2.0.1")))
				.andExpect(redirectedUrl("/admin/setup"));
		mockMvc.perform(get("/admin/setup").session(session).header("User-Agent", LAPTOP))
				.andExpect(status().isOk())
				.andExpect(view().name("admin/setup"))
				.andExpect(model().attribute("deviceLabel", "Windows, Chrome"));

		MvcResult registered = mockMvc.perform(post("/admin/setup").session(session).header("User-Agent", LAPTOP).with(from("10.2.0.1")))
				.andExpect(redirectedUrl("/admin/posts"))
				.andReturn();

		// 브라우저에는 기기 쿠키(자바스크립트로 못 읽음, 관리자 주소에만), DB에는 그 값의 해시만
		Cookie cookie = registered.getResponse().getCookie(AdminAuthService.DEVICE_COOKIE);
		assertThat(cookie).isNotNull();
		assertThat(cookie.isHttpOnly()).isTrue();
		assertThat(cookie.getPath()).isEqualTo("/admin");
		assertThat(cookie.getMaxAge()).isGreaterThan(0);
		AdminDevice device = deviceRepository.findById(AdminDevice.ID).orElseThrow();
		assertThat(device.getLabel()).isEqualTo("Windows, Chrome");
		assertThat(device.getTokenHash()).hasSize(64).isNotEqualTo(cookie.getValue());

		mockMvc.perform(get("/admin/posts").session(session))
				.andExpect(status().isOk())
				.andExpect(view().name("admin/posts"));
	}

	@Test
	void 등록한_뒤에는_등록한_브라우저에서만_비밀번호로_들어간다() throws Exception {
		Cookie laptop = registerLaptop("10.2.0.2");

		// 등록한 브라우저 + 맞는 비밀번호
		mockMvc.perform(post("/admin/login").cookie(laptop).param("password", PASSWORD).with(from("10.2.0.2")))
				.andExpect(redirectedUrl("/admin/posts"));
		// 등록한 브라우저 + 틀린 비밀번호
		mockMvc.perform(post("/admin/login").cookie(laptop).param("password", "wrong-password").with(from("10.2.0.2")))
				.andExpect(view().name("admin/login"))
				.andExpect(model().attribute("error", "비밀번호가 맞지 않습니다."));

		// 다른 브라우저(쿠키 없음, 또는 다른 값)는 맞는 비밀번호를 넣어도 들어가지 못한다
		mockMvc.perform(post("/admin/login").param("password", PASSWORD).with(from("10.2.0.3")))
				.andExpect(status().isForbidden())
				.andExpect(model().attribute("unknownDevice", true));
		mockMvc.perform(post("/admin/login").cookie(new Cookie(AdminAuthService.DEVICE_COOKIE, "guessed-value"))
				.param("password", PASSWORD).with(from("10.2.0.3")))
				.andExpect(status().isForbidden());
		// 등록 화면도 다시 열리지 않는다 (이미 등록한 기기가 있다)
		MockHttpSession other = new MockHttpSession();
		mockMvc.perform(post("/admin/login").session(other).param("password", PASSWORD).with(from("10.2.0.3")))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/admin/setup").session(other)).andExpect(redirectedUrl("/admin/login"));
	}

	@Test
	void 등록된_기기가_있으면_다른_브라우저에는_비밀번호_칸을_보여주지_않는다() throws Exception {
		Cookie laptop = registerLaptop("10.2.0.4");

		mockMvc.perform(get("/admin/login"))
				.andExpect(status().isForbidden())
				.andExpect(model().attribute("unknownDevice", true));
		mockMvc.perform(get("/admin/login").cookie(laptop))
				.andExpect(status().isOk())
				.andExpect(model().attribute("unknownDevice", false));
	}

	@Test
	void 같은_IP에서_비밀번호를_5번_틀리면_맞게_넣어도_15분_동안_막힌다() throws Exception {
		Cookie laptop = registerLaptop("10.2.0.5");
		for (int i = 0; i < 5; i++) {
			mockMvc.perform(post("/admin/login").cookie(laptop).param("password", "wrong-" + i).with(from("10.2.0.6")))
					.andExpect(view().name("admin/login"));
		}

		mockMvc.perform(post("/admin/login").cookie(laptop).param("password", PASSWORD).with(from("10.2.0.6")))
				.andExpect(status().isTooManyRequests());
	}

	@Test
	void 관리자는_글_목록에서_번호를_보고_글을_지운다_CSRF_값이_없으면_막힌다() throws Exception {
		MockHttpSession session = loggedIn("10.2.0.7");
		Post post = postRepository.save(new Post("관리자시험", passwordEncoder.encode("1234"), "메모", "admin-test.jpg", "admin-test-thumb.jpg", null));

		MvcResult list = mockMvc.perform(get("/admin/posts").session(session)).andExpect(status().isOk()).andReturn();
		FeedPage feed = (FeedPage) list.getModelAndView().getModel().get("feed");
		assertThat(feed.posts()).extracting(PostSummary::id).contains(post.getId());

		// 폼에 숨겨 둔 값이 없으면 (다른 사이트가 몰래 보낸 요청) 지우지 않는다
		mockMvc.perform(post("/admin/posts/" + post.getId() + "/delete").session(session))
				.andExpect(status().isUnauthorized());
		assertThat(postRepository.findById(post.getId()).orElseThrow().getStatus()).isEqualTo(PostStatus.ACTIVE);

		mockMvc.perform(post("/admin/posts/" + post.getId() + "/delete").session(session)
				.param("_csrf", (String) session.getAttribute("admin.csrf")).param("page", "1"))
				.andExpect(redirectedUrl("/admin/posts?page=1"))
				.andExpect(flash().attribute("message", post.getId() + "번 글을 지웠습니다."));
		assertThat(postRepository.findById(post.getId()).orElseThrow().getStatus()).isEqualTo(PostStatus.DELETED_BY_ADMIN);
	}

	@Test
	void 로그아웃하면_다시_로그인해야_한다() throws Exception {
		MockHttpSession session = loggedIn("10.2.0.8");

		mockMvc.perform(post("/admin/logout").session(session).param("_csrf", (String) session.getAttribute("admin.csrf")))
				.andExpect(redirectedUrl("/admin/login"));
		mockMvc.perform(get("/admin/posts").session(session))
				.andExpect(redirectedUrl("/admin/login"));
	}

	// 처음 등록을 거쳐 노트북 브라우저의 기기 쿠키를 받는다
	private Cookie registerLaptop(String address) throws Exception {
		MockHttpSession session = new MockHttpSession();
		mockMvc.perform(post("/admin/login").session(session).param("password", PASSWORD).with(from(address)))
				.andExpect(redirectedUrl("/admin/setup"));
		MvcResult result = mockMvc.perform(post("/admin/setup").session(session).header("User-Agent", LAPTOP).with(from(address)))
				.andExpect(redirectedUrl("/admin/posts"))
				.andReturn();
		return result.getResponse().getCookie(AdminAuthService.DEVICE_COOKIE);
	}

	// 등록한 노트북 브라우저로 로그인한 세션
	private MockHttpSession loggedIn(String address) throws Exception {
		Cookie laptop = registerLaptop(address);
		MockHttpSession session = new MockHttpSession();
		mockMvc.perform(post("/admin/login").session(session).cookie(laptop).param("password", PASSWORD).with(from(address)))
				.andExpect(redirectedUrl("/admin/posts"));
		return session;
	}

	private static RequestPostProcessor from(String address) {
		return request -> {
			request.setRemoteAddr(address);
			return request;
		};
	}
}
