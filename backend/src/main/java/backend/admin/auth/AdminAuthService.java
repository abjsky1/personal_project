package backend.admin.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import backend.common.error.BusinessException;
import backend.common.error.ErrorCode;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;

// 관리자 화면(/admin)에 들어오는 사람을 확인한다 (JM-82)
//
// 지금의 방식 (2026-11-14 운영자 결정): 등록한 노트북의 브라우저에서만, 관리자 비밀번호로 들어온다. 휴대폰 인증 앱은 아직 쓰지 않는다.
// ① 등록한 기기인지: 처음 한 번 노트북 브라우저를 관리자 기기로 등록하면, 그 브라우저에 길고 무작위인 기기 쿠키를 심는다
//    (1년, 관리자 주소에만 보냄, 자바스크립트로 읽을 수 없음). DB에는 그 값의 해시만 둔다.
//    등록한 뒤로는 이 쿠키가 맞는 브라우저에서만 로그인 칸이 열린다 → 비밀번호를 알아도 다른 기기에서는 못 들어온다
// ② 관리자 비밀번호: 서버 .env의 APP_ADMIN_PASSWORD. 운영자 API의 열쇠(APP_ADMIN_TOKEN)와 따로라서 그 열쇠는 서버 밖으로 나가지 않는다
// - 들어오면 세션에 "언제까지 관리자인지"를 적는다 (기본 2시간). 로그아웃하면 지운다
// - 같은 IP에서 15분 안에 비밀번호를 5번 틀리면 그 15분이 끝날 때까지 막는다. 들어오고, 틀리고, 막히고, 모르는 기기가 온 것은 모두 로그에 남는다
// - 비밀번호가 비어 있으면(개발 PC 등) 관리자 화면은 없는 주소(404)로 다룬다
@Slf4j
@Service
public class AdminAuthService {

	public static final String DEVICE_COOKIE = "jm_admin_device";
	static final String UNTIL = "admin.until";
	static final String CSRF = "admin.csrf";
	static final String PASSWORD_OK_UNTIL = "admin.passwordOkUntil";

	private static final int MAX_FAILURES = 5;
	private static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
	// 비밀번호가 맞은 뒤 기기를 등록할 수 있는 시간
	private static final Duration REGISTER_WINDOW = Duration.ofMinutes(10);
	private static final Duration DEVICE_COOKIE_AGE = Duration.ofDays(365);
	private static final SecureRandom RANDOM = new SecureRandom();

	private final String password;
	private final Duration sessionLength;
	private final AdminDeviceRepository deviceRepository;

	// IP별 틀린 횟수. 처음 틀린 때부터 15분이 지나면 저절로 지워진다 (서버 메모리에만 둔다)
	private final Cache<String, AtomicInteger> failures = Caffeine.newBuilder()
			.expireAfterWrite(FAILURE_WINDOW)
			.maximumSize(10_000)
			.build();

	public AdminAuthService(
			@Value("${app.admin.password:}") String password,
			@Value("${app.admin.session-minutes:120}") long sessionMinutes,
			AdminDeviceRepository deviceRepository) {
		this.password = password;
		this.sessionLength = Duration.ofMinutes(sessionMinutes);
		this.deviceRepository = deviceRepository;
	}

	public enum LoginResult { LOGGED_IN, NEEDS_REGISTRATION, FAILED, BLOCKED, UNKNOWN_DEVICE }

	// 관리자 화면이 켜져 있는지 (비밀번호가 설정되어 있는지). 꺼져 있으면 없는 주소로 다룬다
	public void requireEnabled() {
		if (password == null || password.isBlank()) {
			throw new BusinessException(ErrorCode.NOT_FOUND);
		}
	}

	public boolean deviceRegistered() {
		return deviceRepository.existsById(AdminDevice.ID);
	}

	// 이 요청이 등록한 기기(브라우저)에서 왔는지: 기기 쿠키 값의 해시가 DB의 해시와 같은지
	public boolean isRegisteredDevice(HttpServletRequest request) {
		Optional<AdminDevice> device = deviceRepository.findById(AdminDevice.ID);
		String token = deviceToken(request);
		return device.isPresent() && token != null && matches(sha256(token), device.get().getTokenHash());
	}

	// 비밀번호로 들어간다.
	// - 등록한 기기가 있으면: 그 기기에서 온 요청만 비밀번호를 본다. 다른 기기는 비밀번호를 보지도 않고 돌려보낸다
	// - 아직 등록한 기기가 없으면: 비밀번호가 맞을 때 기기 등록 단계(NEEDS_REGISTRATION)로 보낸다
	@Transactional
	public LoginResult login(HttpServletRequest request, String givenPassword) {
		String ip = request.getRemoteAddr();
		if (isBlocked(ip)) {
			log.warn("Admin login blocked: ipHash={}", hash(ip));
			return LoginResult.BLOCKED;
		}
		Optional<AdminDevice> device = deviceRepository.findById(AdminDevice.ID);

		if (device.isEmpty()) {
			if (!matches(givenPassword, password)) {
				return fail(ip, "password");
			}
			request.getSession().setAttribute(PASSWORD_OK_UNTIL, Instant.now().plus(REGISTER_WINDOW));
			log.info("Admin password accepted, device registration started: ipHash={}", hash(ip));
			return LoginResult.NEEDS_REGISTRATION;
		}

		String token = deviceToken(request);
		if (token == null || !matches(sha256(token), device.get().getTokenHash())) {
			log.warn("Admin login rejected: unknown device, ipHash={}", hash(ip));
			return LoginResult.UNKNOWN_DEVICE;
		}
		if (!matches(givenPassword, password)) {
			return fail(ip, "password");
		}
		device.get().markUsed();
		startSession(request);
		failures.invalidate(ip);
		log.info("Admin login succeeded: ipHash={}", hash(ip));
		return LoginResult.LOGGED_IN;
	}

	// 기기 등록 중인지: 비밀번호가 맞은 뒤 10분 안이고, 아직 아무 기기도 등록하지 않았을 때
	public boolean registrationPending(HttpSession session) {
		if (session == null) {
			return false;
		}
		Instant until = (Instant) session.getAttribute(PASSWORD_OK_UNTIL);
		return until != null && Instant.now().isBefore(until) && !deviceRegistered();
	}

	// 이 브라우저를 관리자 기기로 등록하고 바로 들어간다
	@Transactional
	public boolean registerDevice(HttpServletRequest request, HttpServletResponse response) {
		HttpSession session = request.getSession(false);
		if (!registrationPending(session)) {
			return false;
		}
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		String token = HexFormat.of().formatHex(bytes);
		String label = deviceLabel(request.getHeader("User-Agent"));
		deviceRepository.save(new AdminDevice(sha256(token), label));

		// HttpOnly: 자바스크립트로 읽을 수 없다. Secure: https로만 보낸다 (배포 서버. 로컬 http에서는 끈다)
		// Path=/admin: 관리자 주소에만 보낸다. SameSite=Lax: 다른 사이트가 몰래 보내는 POST에는 붙지 않는다
		Cookie cookie = new Cookie(DEVICE_COOKIE, token);
		cookie.setHttpOnly(true);
		cookie.setSecure(request.isSecure());
		cookie.setPath("/admin");
		cookie.setMaxAge((int) DEVICE_COOKIE_AGE.toSeconds());
		cookie.setAttribute("SameSite", "Lax");
		response.addCookie(cookie);

		session.removeAttribute(PASSWORD_OK_UNTIL);
		startSession(request);
		log.info("Admin device registered: device={}, ipHash={}", label, hash(request.getRemoteAddr()));
		log.info("Admin login succeeded: ipHash={}", hash(request.getRemoteAddr()));
		return true;
	}

	public boolean isLoggedIn(HttpSession session) {
		if (session == null) {
			return false;
		}
		Instant until = (Instant) session.getAttribute(UNTIL);
		return until != null && Instant.now().isBefore(until);
	}

	// 관리자 화면의 폼마다 숨겨 두는 값 (CSRF 방지). 다른 사이트가 몰래 보낸 요청에는 이 값이 없어서 막힌다
	public String csrfToken(HttpSession session) {
		return session == null ? null : (String) session.getAttribute(CSRF);
	}

	public boolean csrfMatches(HttpSession session, String given) {
		return matches(given, csrfToken(session));
	}

	public void logout(HttpSession session) {
		if (session != null) {
			session.removeAttribute(UNTIL);
			session.removeAttribute(CSRF);
		}
		log.info("Admin logged out");
	}

	// 브라우저가 보낸 정보로 기기를 알아볼 이름을 만든다 (예: "Windows, Chrome")
	public static String deviceLabel(String userAgent) {
		if (userAgent == null || userAgent.isBlank()) {
			return "알 수 없는 기기";
		}
		String os = userAgent.contains("Windows") ? "Windows"
				: userAgent.contains("iPhone") ? "iPhone"
				: userAgent.contains("iPad") ? "iPad"
				: userAgent.contains("Android") ? "Android"
				: userAgent.contains("Mac OS X") ? "Mac"
				: userAgent.contains("Linux") ? "Linux" : "기타 기기";
		String browser = userAgent.contains("Edg/") ? "Edge"
				: userAgent.contains("Whale/") ? "Whale"
				: userAgent.contains("SamsungBrowser/") ? "삼성 인터넷"
				: userAgent.contains("Firefox/") ? "Firefox"
				: userAgent.contains("Chrome/") ? "Chrome"
				: userAgent.contains("Safari/") ? "Safari" : "기타 브라우저";
		return os + ", " + browser;
	}

	private void startSession(HttpServletRequest request) {
		request.getSession();
		// 로그인 전의 세션 번호를 버리고 새 번호를 받는다 (남이 미리 심어 둔 세션 번호로 관리자 세션을 가로채는 것을 막는다)
		request.changeSessionId();
		HttpSession session = request.getSession();
		session.setAttribute(UNTIL, Instant.now().plus(sessionLength));
		byte[] token = new byte[24];
		RANDOM.nextBytes(token);
		session.setAttribute(CSRF, HexFormat.of().formatHex(token));
	}

	private static String deviceToken(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie cookie : cookies) {
			if (DEVICE_COOKIE.equals(cookie.getName())) {
				return cookie.getValue();
			}
		}
		return null;
	}

	private boolean isBlocked(String ip) {
		AtomicInteger count = failures.getIfPresent(ip);
		return count != null && count.get() >= MAX_FAILURES;
	}

	private LoginResult fail(String ip, String reason) {
		failures.get(ip, key -> new AtomicInteger()).incrementAndGet();
		log.warn("Admin login failed: reason={}, ipHash={}", reason, hash(ip));
		return LoginResult.FAILED;
	}

	// 비밀값 비교는 끝까지 비교한다 (걸린 시간으로 몇 글자까지 맞았는지 짐작하지 못하게)
	private static boolean matches(String given, String expected) {
		if (given == null || expected == null || expected.isBlank()) {
			return false;
		}
		return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
	}

	private static String sha256(String text) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	// IP 원문은 로그에 남기지 않고 SHA-256 해시의 앞 12글자로 남긴다 (기획서 8절)
	private static String hash(String ip) {
		return sha256(ip).substring(0, 12);
	}
}
