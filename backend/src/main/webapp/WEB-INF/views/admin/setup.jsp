<%-- 관리자 기기 등록 (JM-82). 비밀번호가 맞은 뒤 10분 동안, 아직 아무 기기도 등록하지 않았을 때만 열린다 --%>
<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>관리자 기기 등록 - 찍먹 관리자</title>
<link rel="stylesheet" href="<c:url value='/css/app.css' />">
</head>
<body>
<main class="admin-auth">
	<h1>관리자 기기 등록</h1>
	<p class="admin-device">이 브라우저: <strong><c:out value="${deviceLabel}" /></strong></p>
	<ul class="admin-steps">
		<li>등록하면 앞으로 <strong>이 브라우저에서만</strong> 관리자 화면에 들어올 수 있습니다.</li>
		<li>다른 브라우저나 휴대폰에서는 비밀번호를 알아도 들어올 수 없습니다.</li>
		<li>이 브라우저의 쿠키를 지우거나 기기를 바꾸면, 서버에서 등록을 지운 뒤 다시 등록합니다.</li>
	</ul>

	<form method="post" action="/admin/setup" class="admin-auth-form">
		<button type="submit">이 브라우저를 관리자 기기로 등록</button>
	</form>
	<p class="hint">관리자 화면을 열 노트북이 아니라면 등록하지 말고 이 창을 닫아 주세요.</p>
</main>
</body>
</html>
