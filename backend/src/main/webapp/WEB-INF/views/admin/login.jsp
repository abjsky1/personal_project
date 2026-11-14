<%-- 관리자 로그인 (JM-82): 등록한 노트북 브라우저에서 관리자 비밀번호로 들어간다.
     등록한 기기가 있는데 이 브라우저가 그 기기가 아니면 비밀번호 칸 없이 안내만 보여 준다 --%>
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

	<c:choose>
		<c:when test="${unknownDevice}">
			<p class="error">관리자 화면은 등록한 기기에서만 열립니다.</p>
			<p class="hint">기기를 바꾸려면 서버에서 등록을 지운 뒤 새 기기에서 다시 등록합니다 (기획서 16절).</p>
		</c:when>
		<c:otherwise>
			<form method="post" action="/admin/login" class="admin-auth-form">
				<label for="password">관리자 비밀번호</label>
				<input type="password" id="password" name="password" autocomplete="current-password" required autofocus>
				<c:if test="${not empty error}"><p class="error"><c:out value="${error}" /></p></c:if>
				<button type="submit">들어가기</button>
			</form>
			<c:if test="${not deviceRegistered}">
				<p class="hint">처음 들어가면 이 브라우저를 관리자 기기로 등록합니다. 앞으로 관리자 화면을 열 노트북에서 들어가 주세요.</p>
			</c:if>
		</c:otherwise>
	</c:choose>
</main>
</body>
</html>
