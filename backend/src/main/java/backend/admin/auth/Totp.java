// 관리자 화면에 들어오는 사람을 확인하는 코드를 두는 패키지 (JM-82)
package backend.admin.auth;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

// 휴대폰 인증 앱(Google Authenticator 등)의 6자리 숫자를 만들고 맞춰 보는 도구 (TOTP, 표준 RFC 6238)
// 서버와 휴대폰이 같은 비밀값을 나눠 갖고, "지금 시각을 30초 단위로 센 칸 번호"와 비밀값으로 같은 숫자를 각자 계산한다.
// → 숫자는 30초마다 바뀌고, 비밀값을 모르면 다음 숫자를 알 수 없다. 관리자 비밀번호가 새어도 휴대폰이 없으면 들어올 수 없다
// 라이브러리 없이 자바 기본 기능(HmacSHA1)으로 만든다. 비밀값은 인증 앱에 넣는 표준 모양(Base32 글자)으로 다룬다
public final class Totp {

	static final long STEP_SECONDS = 30;
	private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
	private static final SecureRandom RANDOM = new SecureRandom();

	// 객체를 만들 일이 없는 도구 클래스라 생성자를 막아 둔다
	private Totp() {
	}

	// 새 비밀값: 무작위 20바이트(160비트)를 Base32 글자 32개로
	public static String newSecret() {
		byte[] bytes = new byte[20];
		RANDOM.nextBytes(bytes);
		return encodeBase32(bytes);
	}

	// 그 시각이 들어 있는 30초 칸의 번호
	public static long stepAt(Instant now) {
		return Math.floorDiv(now.getEpochSecond(), STEP_SECONDS);
	}

	// 입력한 숫자가 맞으면 맞은 칸 번호를, 틀리면 -1을 돌려준다.
	// 휴대폰과 서버의 시계가 조금 어긋날 수 있어서 바로 앞 칸과 뒤 칸(앞뒤 30초)까지 받아 준다
	public static long verify(String secret, String code, Instant now) {
		if (secret == null || code == null) {
			return -1;
		}
		String digits = code.replace(" ", "").strip();
		if (!digits.matches("[0-9]{6}")) {
			return -1;
		}
		byte[] key = decodeBase32(secret);
		long step = stepAt(now);
		for (long candidate = step - 1; candidate <= step + 1; candidate++) {
			// 비밀값을 다루는 비교라서 끝까지 비교하는 MessageDigest.isEqual을 쓴다 (AdminTokenInterceptor와 같은 이유)
			if (MessageDigest.isEqual(codeAt(key, candidate).getBytes(StandardCharsets.US_ASCII),
					digits.getBytes(StandardCharsets.US_ASCII))) {
				return candidate;
			}
		}
		return -1;
	}

	// 그 시각에 인증 앱이 보여 줄 6자리 숫자 (서버는 맞춰 볼 때만 쓰고, 테스트가 휴대폰 대신 숫자를 만들 때 쓴다)
	public static String codeFor(String secret, Instant time) {
		return codeAt(decodeBase32(secret), stepAt(time));
	}

	// 칸 번호 하나의 6자리 숫자 (표준 계산법: HMAC-SHA1 → 동적 잘라내기 → 1,000,000으로 나눈 나머지)
	static String codeAt(byte[] key, long step) {
		try {
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(key, "HmacSHA1"));
			byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
			int offset = hash[hash.length - 1] & 0x0f;
			int binary = ((hash[offset] & 0x7f) << 24)
					| ((hash[offset + 1] & 0xff) << 16)
					| ((hash[offset + 2] & 0xff) << 8)
					| (hash[offset + 3] & 0xff);
			return String.format("%06d", binary % 1_000_000);
		} catch (GeneralSecurityException e) {
			// HmacSHA1은 모든 자바에 들어 있어서 실제로는 일어나지 않는다
			throw new IllegalStateException(e);
		}
	}

	// 인증 앱에 등록하는 주소. QR 코드에 담아 보여 준다
	// account: 앱에 보이는 이름. 서버 주소를 넣어 "jjikmuk (admin@jjikmuk.com)"처럼 보이게 해서, 로컬에서 시험한 등록과 헷갈리지 않게 한다
	public static String otpauthUri(String secret, String account) {
		return "otpauth://totp/jjikmuk:" + URLEncoder.encode(account, StandardCharsets.UTF_8) + "?secret=" + secret
				+ "&issuer=jjikmuk&algorithm=SHA1&digits=6&period=30";
	}

	// 바이트 → Base32 글자 (5비트씩 잘라 글자 하나로). 끝의 '='는 붙이지 않는다 (인증 앱은 없어도 읽는다)
	static String encodeBase32(byte[] bytes) {
		StringBuilder out = new StringBuilder();
		int buffer = 0;
		int bits = 0;
		for (byte b : bytes) {
			buffer = (buffer << 8) | (b & 0xff);
			bits += 8;
			while (bits >= 5) {
				out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
				bits -= 5;
			}
			buffer &= (1 << bits) - 1;
		}
		if (bits > 0) {
			out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
		}
		return out.toString();
	}

	// Base32 글자 → 바이트. 띄어쓰기, 소문자, 끝의 '='는 무시한다
	static byte[] decodeBase32(String text) {
		String clean = text.replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int buffer = 0;
		int bits = 0;
		for (char c : clean.toCharArray()) {
			int value = BASE32.indexOf(c);
			if (value < 0) {
				throw new IllegalArgumentException("Base32 글자가 아닙니다");
			}
			buffer = (buffer << 5) | value;
			bits += 5;
			if (bits >= 8) {
				out.write((buffer >> (bits - 8)) & 0xff);
				bits -= 8;
				buffer &= (1 << bits) - 1;
			}
		}
		return out.toByteArray();
	}
}
