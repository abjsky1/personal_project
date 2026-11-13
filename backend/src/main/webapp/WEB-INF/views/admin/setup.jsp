<%-- 관리자 휴대폰 인증 앱 등록 (JM-82). 비밀번호가 맞은 뒤 10분 동안, 아직 아무 휴대폰도 등록하지 않았을 때만 열린다 --%>
<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>인증 앱 등록 - 찍먹 관리자</title>
<link rel="stylesheet" href="<c:url value='/css/app.css' />">
</head>
<body>
<main class="admin-auth">
	<h1>휴대폰 인증 앱 등록</h1>
	<ol class="admin-steps">
		<li>휴대폰에 인증 앱(Google Authenticator 또는 Microsoft Authenticator)을 설치합니다.</li>
		<li>앱에서 "QR 코드 스캔"을 누르고 아래 QR 코드를 찍습니다.</li>
		<li>앱에 나온 6자리 숫자를 아래 칸에 넣습니다.</li>
	</ol>

	<%-- QR 코드: 서버가 만든 SVG를 그대로 넣는다 (AdminQr) --%>
	<div class="admin-qr">${qrSvg}</div>
	<details class="admin-secret">
		<summary>QR 코드를 찍을 수 없을 때</summary>
		<p>앱의 "설정 키 입력"에 이름은 jjikmuk, 키는 아래 글자를 넣고, 시간 기준으로 고릅니다.</p>
		<code><c:out value="${secretGroups}" /></code>
	</details>

	<form method="post" action="/admin/setup" class="admin-auth-form">
		<label for="code">앱에 나온 6자리 숫자</label>
		<input type="text" id="code" name="code" inputmode="numeric" autocomplete="one-time-code"
			pattern="[0-9]{6}" maxlength="6" required autofocus>
		<c:if test="${not empty error}"><p class="error"><c:out value="${error}" /></p></c:if>
		<button type="submit">등록하고 들어가기</button>
	</form>
	<p class="hint">등록한 뒤에는 이 휴대폰의 숫자가 있어야 관리자 화면에 들어올 수 있습니다. 휴대폰을 바꾸거나 잃어버리면 기획서 16절의 순서대로 다시 등록합니다.</p>
</main>
</body>
</html>
