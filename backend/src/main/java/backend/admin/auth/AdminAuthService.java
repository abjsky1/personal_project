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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;

// 관리자 화면(/admin)에 들어오는 사람을 확인한다 (JM-82)
//
// 두 겹으로 확인한다: ① 관리자 비밀번호(서버 .env의 APP_ADMIN_PASSWORD) ② 휴대폰 인증 앱의 6자리 숫자(Totp)
// → 비밀번호가 새어도 휴대폰이 없으면 못 들어온다. 운영자 API의 열쇠(APP_ADMIN_TOKEN)와는 따로 두어, 그 열쇠는 지금처럼 서버 밖으로 나가지 않는다.
// - 처음 들어올 때(인증 앱을 아직 등록하지 않았을 때): 비밀번호가 맞으면 QR 코드를 보여 주고, 앱에 뜬 숫자가 맞으면 등록하고 들어간다
// - 들어오면 세션에 "언제까지 관리자인지"를 적는다 (기본 2시간). 로그아웃하면 지운다
// - 같은 IP에서 15분 안에 5번 틀리면 그 15분이 끝날 때까지 막는다. 들어오고, 틀리고, 막힌 것은 모두 로그에 남는다
//   (로그 "Admin login succeeded"에 CloudWatch 경보를 걸면 관리자 로그인마다 메일로 알 수 있다, 기획서 16절)
// - 비밀번호가 비어 있으면(개발 PC 등) 관리자 화면은 없는 주소(404)로 다룬다
@Slf4j
@Service
public class AdminAuthService {

	static final String UNTIL = "admin.until";
	static final String CSRF = "admin.csrf";
	static final String PASSWORD_OK_UNTIL = "admin.passwordOkUntil";
	static final String SETUP_SECRET = "admin.setupSecret";

	private static final int MAX_FAILURES = 5;
	private static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
	// 비밀번호가 맞은 뒤 인증 앱을 등록할 수 있는 시간
	private static final Duration SETUP_WINDOW = Duration.ofMinutes(10);
	private static final SecureRandom RANDOM = new SecureRandom();

	private final String password;
	private final Duration sessionLength;
	private final AdminOtpRepository otpRepository;

	// IP별 틀린 횟수. 처음 틀린 때부터 15분이 지나면 저절로 지워진다 (서버 메모리에만 둔다)
	private final Cache<String, AtomicInteger> failures = Caffeine.newBuilder()
			.expireAfterWrite(FAILURE_WINDOW)
			.maximumSize(10_000)
			.build();

	public AdminAuthService(
			@Value("${app.admin.password:}") String password,
			@Value("${app.admin.session-minutes:120}") long sessionMinutes,
			AdminOtpRepository otpRepository) {
		this.password = password;
		this.sessionLength = Duration.ofMinutes(sessionMinutes);
		this.otpRepository = otpRepository;
	}

	public enum LoginResult { LOGGED_IN, NEEDS_SETUP, FAILED, BLOCKED }

	// 관리자 화면이 켜져 있는지 (비밀번호가 설정되어 있는지). 꺼져 있으면 없는 주소로 다룬다
	public void requireEnabled() {
		if (password == null || password.isBlank()) {
			throw new BusinessException(ErrorCode.NOT_FOUND);
		}
	}

	public boolean otpRegistered() {
		return otpRepository.existsById(AdminOtp.ID);
	}

	// 비밀번호와 6자리 숫자로 들어간다. 인증 앱을 아직 등록하지 않았으면 비밀번호만 보고 등록 단계(NEEDS_SETUP)로 보낸다
	// 무엇이 틀렸는지(비밀번호인지 숫자인지)는 알려 주지 않는다
	@Transactional
	public LoginResult login(HttpServletRequest request, String givenPassword, String code) {
		String ip = request.getRemoteAddr();
		if (isBlocked(ip)) {
			log.warn("Admin login blocked: ipHash={}", hash(ip));
			return LoginResult.BLOCKED;
		}
		boolean passwordOk = matches(givenPassword, password);
		Optional<AdminOtp> otp = otpRepository.findById(AdminOtp.ID);

		if (otp.isEmpty()) {
			if (!passwordOk) {
				return fail(ip, "password");
			}
			HttpSession session = request.getSession();
			session.setAttribute(PASSWORD_OK_UNTIL, Instant.now().plus(SETUP_WINDOW));
			session.setAttribute(SETUP_SECRET, Totp.newSecret());
			log.info("Admin password accepted, OTP setup started: ipHash={}", hash(ip));
			return LoginResult.NEEDS_SETUP;
		}

		long step = Totp.verify(otp.get().getSecret(), code, Instant.now());
		// 숫자가 맞아도 이미 한 번 쓴 숫자면 받지 않는다. useStep이 쓴 칸을 남기면 트랜잭션이 끝날 때 DB에 저장된다
		if (!passwordOk || step < 0 || !otp.get().useStep(step)) {
			return fail(ip, "password-or-code");
		}
		startSession(request);
		failures.invalidate(ip);
		log.info("Admin login succeeded: ipHash={}", hash(ip));
		return LoginResult.LOGGED_IN;
	}

	// 등록 중인 비밀값. 비밀번호가 맞은 뒤 10분 안이고 아직 아무 휴대폰도 등록하지 않았을 때만 있다 (아니면 null)
	public String pendingSetupSecret(HttpSession session) {
		if (session == null) {
			return null;
		}
		Instant until = (Instant) session.getAttribute(PASSWORD_OK_UNTIL);
		String secret = (String) session.getAttribute(SETUP_SECRET);
		if (until == null || secret == null || Instant.now().isAfter(until) || otpRegistered()) {
			return null;
		}
		return secret;
	}

	// 인증 앱 등록을 마친다: 앱에 뜬 숫자가 맞으면 비밀값을 저장하고 바로 들어간다
	@Transactional
	public boolean completeSetup(HttpServletRequest request, String code) {
		String ip = request.getRemoteAddr();
		HttpSession session = request.getSession(false);
		String secret = pendingSetupSecret(session);
		if (secret == null || isBlocked(ip)) {
			return false;
		}
		long step = Totp.verify(secret, code, Instant.now());
		if (step < 0) {
			fail(ip, "setup-code");
			return false;
		}
		otpRepository.save(new AdminOtp(secret, step));
		session.removeAttribute(PASSWORD_OK_UNTIL);
		session.removeAttribute(SETUP_SECRET);
		startSession(request);
		failures.invalidate(ip);
		log.info("Admin OTP registered: ipHash={}", hash(ip));
		log.info("Admin login succeeded: ipHash={}", hash(ip));
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

	// IP 원문은 로그에 남기지 않고 SHA-256 해시의 앞 12글자로 남긴다 (기획서 8절)
	private static String hash(String ip) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(ip.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest).substring(0, 12);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
