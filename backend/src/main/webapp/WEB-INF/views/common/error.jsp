<%-- 에러 화면: PageExceptionHandler가 상태(status), 메시지(message), 요청 번호(traceId)를 넘겨 준다. --%>
<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>문제가 생겼어요</title>
<link rel="stylesheet" href="/css/app.css">
</head>
<body>
<main class="error-page">
	<p class="status">${status}</p>
	<p><c:out value="${message}" /></p>

	<%-- 사용자가 이 번호를 알려 주면, 서버 로그에서 같은 [번호]가 찍힌 줄을 찾아 원인을 본다. --%>
	<c:if test="${not empty traceId}">
		<p class="hint">문의할 때 이 번호를 알려 주세요: <c:out value="${traceId}" /></p>
	</c:if>

	<a class="button" href="/">목록으로</a>

	<%-- 공통 바닥글 (개인정보처리방침 링크) --%>
	<%@ include file="/WEB-INF/views/common/footer.jspf" %>
</main>
</body>
</html>
