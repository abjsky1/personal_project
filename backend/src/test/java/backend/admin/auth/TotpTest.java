package backend.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

// 휴대폰 인증 앱의 6자리 숫자 계산(Totp)이 표준과 같은지 확인하는 테스트 (JM-82)
class TotpTest {

	// 표준 문서(RFC 6238 부록 B)의 시험용 비밀값. 표의 8자리 숫자 중 뒤 6자리가 인증 앱이 보여 주는 숫자다
	private static final byte[] RFC_KEY = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

	@Test
	void 표준_문서의_시험_값과_같은_숫자를_만든다() {
		assertThat(Totp.codeAt(RFC_KEY, 59L / 30)).isEqualTo("287082");
		assertThat(Totp.codeAt(RFC_KEY, 1111111109L / 30)).isEqualTo("081804");
		assertThat(Totp.codeAt(RFC_KEY, 1111111111L / 30)).isEqualTo("050471");
		assertThat(Totp.codeAt(RFC_KEY, 1234567890L / 30)).isEqualTo("005924");
		assertThat(Totp.codeAt(RFC_KEY, 2000000000L / 30)).isEqualTo("279037");
		assertThat(Totp.codeAt(RFC_KEY, 20000000000L / 30)).isEqualTo("353130");
	}

	@Test
	void Base32로_바꿨다가_되돌리면_같다() {
		assertThat(Totp.encodeBase32(RFC_KEY)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
		assertThat(Totp.decodeBase32("gezd gnbv gy3t qojq gezd gnbv gy3t qojq")).isEqualTo(RFC_KEY);

		String secret = Totp.newSecret();
		assertThat(secret).hasSize(32).matches("[A-Z2-7]+");
		assertThat(Totp.encodeBase32(Totp.decodeBase32(secret))).isEqualTo(secret);
	}

	@Test
	void 앞뒤_30초까지는_받아_주고_그보다_멀거나_모양이_틀리면_받지_않는다() {
		String secret = Totp.newSecret();
		byte[] key = Totp.decodeBase32(secret);
		Instant now = Instant.parse("2026-11-13T10:00:15Z");
		long step = Totp.stepAt(now);

		assertThat(Totp.verify(secret, Totp.codeAt(key, step), now)).isEqualTo(step);
		assertThat(Totp.verify(secret, Totp.codeAt(key, step - 1), now)).isEqualTo(step - 1);
		assertThat(Totp.verify(secret, Totp.codeAt(key, step + 1), now)).isEqualTo(step + 1);
		assertThat(Totp.verify(secret, Totp.codeAt(key, step - 5), now)).isEqualTo(-1);

		assertThat(Totp.verify(secret, "12345", now)).isEqualTo(-1);
		assertThat(Totp.verify(secret, "abcdef", now)).isEqualTo(-1);
		assertThat(Totp.verify(secret, null, now)).isEqualTo(-1);
	}

	@Test
	void 인증_앱에_등록하는_주소에_비밀값과_서버_이름이_들어간다() {
		assertThat(Totp.otpauthUri("ABCDEFGH", "admin@jjikmuk.com"))
				.startsWith("otpauth://totp/jjikmuk:admin%40jjikmuk.com?secret=ABCDEFGH")
				.contains("issuer=jjikmuk", "digits=6", "period=30");
	}
}
