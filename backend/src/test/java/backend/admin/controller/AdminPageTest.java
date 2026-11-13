package backend.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.Instant;

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

import backend.admin.auth.AdminOtp;
import backend.admin.auth.AdminOtpRepository;
import backend.admin.auth.Totp;
import backend.post.model.dto.FeedPage;
import backend.post.model.dto.PostSummary;
import backend.post.model.entity.Post;
import backend.post.model.entity.PostStatus;
import backend.post.model.repository.PostRepository;

// 관리자 화면(/admin)의 로그인(비밀번호와 휴대폰 인증 6자리), 인증 앱 등록, 글 목록과 삭제를 확인하는 테스트 (JM-82)
// 틀린 횟수는 IP마다 세므로, 테스트끼리 섞이지 않게 테스트마다 다른 IP를 쓴다
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.admin.password=test-admin-password")
class AdminPageTest {

	private static final String PASSWORD = "test-admin-password";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AdminOtpRepository otpRepository;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	// 개발 DB에 등록해 둔 인증 앱이 있어도 테스트마다 비우고 시작한다 (테스트가 끝나면 되돌아간다)
	@BeforeEach
	void clearOtp() {
		otpRepository.deleteAllInBatch();
	}

	@Test
	void 로그인하지_않으면_관리자_화면에서_로그인_화면으로_보낸다() throws Exception {
		mockMvc.perform(get("/admin/posts"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/admin/login"));
	}

	@Test
	void 처음에는_비밀번호가_맞으면_휴대폰_인증_앱을_등록하고_들어간다() throws Exception {
		MockHttpSession session = new MockHttpSession();

		mockMvc.perform(post("/admin/login").session(session).param("password", PASSWORD).with(ip("10.1.0.1")))
				.andExpect(redirectedUrl("/admin/setup"));
		mockMvc.perform(get("/admin/setup").session(session))
				.andExpect(status().isOk())
				.andExpect(view().name("admin/setup"))
				.andExpect(model().attribute("qrSvg", containsString("<svg")));

		// 휴대폰 앱이 하는 계산을 테스트가 대신 한다
		String secret = (String) session.getAttribute("admin.setupSecret");
		mockMvc.perform(post("/admin/setup").session(session).param("code", codeNow(secret)).with(ip("10.1.0.1")))
				.andExpect(redirectedUrl("/admin/posts"));

		assertThat(otpRepository.findById(AdminOtp.ID)).get().extracting(AdminOtp::getSecret).isEqualTo(secret);
		mockMvc.perform(get("/admin/posts").session(session))
				.andExpect(status().isOk())
				.andExpect(view().name("admin/posts"));
	}

	@Test
	void 등록한_뒤에는_비밀번호와_6자리가_모두_맞아야_들어가고_한_번_쓴_숫자는_다시_못_쓴다() throws Exception {
		String secret = registerOtp();
		String code = codeNow(secret);

		// 숫자가 틀림
		mockMvc.perform(post("/admin/login").param("password", PASSWORD).param("code", otherCode(code)).with(ip("10.1.0.2")))
				.andExpect(view().name("admin/login"))
				.andExpect(model().attribute("error", "비밀번호나 인증 숫자가 맞지 않습니다."));
		// 비밀번호가 틀림 (숫자는 맞음)
		mockMvc.perform(post("/admin/login").param("password", "wrong-password").param("code", code).with(ip("10.1.0.2")))
				.andExpect(view().name("admin/login"));

		// 둘 다 맞으면 들어간다
		mockMvc.perform(post("/admin/login").session(new MockHttpSession()).param("password", PASSWORD).param("code", code)
				.with(ip("10.1.0.2")))
				.andExpect(redirectedUrl("/admin/posts"));

		// 같은 숫자를 한 번 더 넣으면 받지 않는다 (어깨너머로 본 숫자를 바로 다시 쓰는 경우)
		mockMvc.perform(post("/admin/login").param("password", PASSWORD).param("code", code).with(ip("10.1.0.3")))
				.andExpect(view().name("admin/login"));
	}

	@Test
	void 같은_IP에서_5번_틀리면_맞게_넣어도_15분_동안_막힌다() throws Exception {
		String secret = registerOtp();
		for (int i = 0; i < 5; i++) {
			mockMvc.perform(post("/admin/login").param("password", "wrong-" + i).param("code", "000000").with(ip("10.1.0.4")))
					.andExpect(view().name("admin/login"));
		}

		mockMvc.perform(post("/admin/login").param("password", PASSWORD).param("code", codeNow(secret)).with(ip("10.1.0.4")))
				.andExpect(status().isTooManyRequests())
				.andExpect(model().attribute("error", containsString("막혔습니다")));
	}

	@Test
	void 관리자는_글_목록에서_번호를_보고_글을_지운다_CSRF_값이_없으면_막힌다() throws Exception {
		MockHttpSession session = loggedIn("10.1.0.5");
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
		MockHttpSession session = loggedIn("10.1.0.6");

		mockMvc.perform(post("/admin/logout").session(session).param("_csrf", (String) session.getAttribute("admin.csrf")))
				.andExpect(redirectedUrl("/admin/login"));
		mockMvc.perform(get("/admin/posts").session(session))
				.andExpect(redirectedUrl("/admin/login"));
	}

	// 인증 앱이 등록된 상태를 만든다 (마지막으로 쓴 칸은 아주 옛날로)
	private String registerOtp() {
		String secret = Totp.newSecret();
		otpRepository.save(new AdminOtp(secret, 0));
		return secret;
	}

	private MockHttpSession loggedIn(String address) throws Exception {
		String secret = registerOtp();
		MockHttpSession session = new MockHttpSession();
		mockMvc.perform(post("/admin/login").session(session).param("password", PASSWORD).param("code", codeNow(secret))
				.with(ip(address)))
				.andExpect(redirectedUrl("/admin/posts"));
		return session;
	}

	// 휴대폰 인증 앱이 지금 보여 줄 숫자
	private static String codeNow(String secret) {
		return Totp.codeFor(secret, Instant.now());
	}

	// 맞는 숫자의 첫 자리만 바꾼 틀린 숫자
	private static String otherCode(String code) {
		return (char) ('0' + (code.charAt(0) - '0' + 5) % 10) + code.substring(1);
	}

	private static RequestPostProcessor ip(String address) {
		return request -> {
			request.setRemoteAddr(address);
			return request;
		};
	}
}
