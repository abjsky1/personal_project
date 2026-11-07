<%-- 피드백 게시판 목록 --%>
<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>피드백 · 찍먹</title>
<link rel="stylesheet" href="<c:url value='/css/app.css' />">
</head>
<body>
<main>
	<header class="top">
		<div>
			<h1>피드백</h1>
			<%-- 상단 메뉴: 피드(사진 목록)와 피드백 게시판을 오간다. 지금 있는 화면에 active --%>
			<nav class="site-nav">
				<a href="/">피드</a>
				<a href="/feedback" class="active">피드백</a>
			</nav>
		</div>
		<a class="button" href="/feedback/new">피드백 남기기</a>
	</header>

	<p class="hint">불편한 점, 바라는 점, 글 · 댓글 삭제 요청을 남겨 주세요. 운영자가 보고 처리 상태와 답변을 남깁니다.</p>

	<c:if test="${empty page.feedbacks}">
		<p class="empty">아직 피드백이 없어요.</p>
	</c:if>

	<ul class="feedback-list">
		<c:forEach var="feedback" items="${page.feedbacks}">
			<li>
				<%-- 처리 상태 배지: 글자와 색은 FeedbackProgress에서 가져온다 --%>
				<span class="badge ${feedback.progress.cssClass}"><c:out value="${feedback.progress.label}" /></span>
				<%-- 제목·닉네임은 사용자 입력이라 c:out (XSS 방지) --%>
				<a href="/feedback/${feedback.id}"><c:out value="${feedback.title}" /></a>
				<c:if test="${feedback.hasReply}"><span class="reply-mark">답변 있음</span></c:if>
				<p class="meta"><strong><c:out value="${feedback.nickname}" /></strong> · <c:out value="${feedback.createdAt}" /></p>
			</li>
		</c:forEach>
	</ul>

	<%-- 쪽 번호 줄 (JM-57): 피드와 같은 common/pager.jspf를 쓴다 --%>
	<c:set var="nav" value="${page.nav}" />
	<c:set var="pagerBase" value="/feedback?page=" />
	<%@ include file="/WEB-INF/views/common/pager.jspf" %>

	<%@ include file="/WEB-INF/views/common/footer.jspf" %>
</main>
</body>
</html>
