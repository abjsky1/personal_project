package backend.admin.auth;

import java.util.Map;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

// 인증 앱 등록용 QR 코드를 SVG(글자로 된 그림)로 만든다 (JM-82)
// QR의 칸 계산은 ZXing 라이브러리가 하고, 검은 칸을 SVG 사각형 경로로 옮긴다 → 그림 파일 없이 화면에 바로 넣을 수 있다
public final class AdminQr {

	private AdminQr() {
	}

	public static String svg(String text) {
		try {
			// 크기 0, 0: 칸 하나를 점 하나로 (가장 작은 크기). 화면에서 CSS로 키운다. 둘레에 흰 칸 2줄
			BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
					Map.of(EncodeHintType.MARGIN, 2, EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
			StringBuilder path = new StringBuilder();
			for (int y = 0; y < matrix.getHeight(); y++) {
				for (int x = 0; x < matrix.getWidth(); x++) {
					if (matrix.get(x, y)) {
						path.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
					}
				}
			}
			return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + matrix.getWidth() + " " + matrix.getHeight()
					+ "\" shape-rendering=\"crispEdges\" role=\"img\" aria-label=\"인증 앱에 등록할 QR 코드\">"
					+ "<rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><path fill=\"#000\" d=\"" + path + "\"/></svg>";
		} catch (WriterException e) {
			throw new IllegalStateException(e);
		}
	}
}
