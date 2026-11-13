<%-- 관리자 로그인 (JM-82): 관리자 비밀번호와 휴대폰 인증 앱의 6자리 숫자. 인증 앱을 아직 등록하지 않았으면 비밀번호만 묻는다 --%>
<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>관리자 로그인 - 찍먹</title>
<link rel="stylesheet" href="<c:url value='/css/app.css' />">
</head>
<body>
<main class="admin-auth">
	<h1>찍먹 관리자</h1>
	<c:if test="${not empty message}"><p class="admin-flash"><c:out value="${message}" /></p></c:if>

	<form method="post" action="/admin/login" class="admin-auth-form">
		<label for="password">관리자 비밀번호</label>
		<input type="password" id="password" name="password" autocomplete="current-password" required autofocus>
		<c:if test="${otpRegistered}">
			<label for="code">휴대폰 인증 앱의 6자리 숫자</label>
			<input type="text" id="code" name="code" inputmode="numeric" autocomplete="one-time-code"
				pattern="[0-9]{6}" maxlength="6" required>
		</c:if>
		<c:if test="${not empty error}"><p class="error"><c:out value="${error}" /></p></c:if>
		<button type="submit">들어가기</button>
	</form>

	<c:if test="${not otpRegistered}">
		<p class="hint">처음 들어갈 때는 비밀번호가 맞으면 휴대폰 인증 앱을 등록하는 화면이 나옵니다.</p>
	</c:if>
</main>
</body>
</html>
