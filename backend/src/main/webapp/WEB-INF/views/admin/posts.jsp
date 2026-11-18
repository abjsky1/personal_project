<%-- 관리자 글 목록 (JM-82): 피드에 보이는 글(지우지 않은 글)을 최신순으로. 번호, 사진, 닉네임, 시각, 숫자, AI 결과를 보고 지운다.
     첫 쪽 위에는 AI가 음식이 아니라고 봐서 사진을 가린 글(검토할 사진, JM-80)을 모아 두고 올리거나 지운다.
     한 줄의 모양은 post-row.jspf --%>
<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>글 목록 - 찍먹 관리자</title>
<link rel="stylesheet" href="<c:url value='/css/app.css' />">
</head>
<body>
<main class="admin">
	<c:set var="adminTab" value="posts" />
	<%@ include file="/WEB-INF/views/admin/header.jspf" %>

	<c:if test="${not empty message}"><p class="admin-flash"><c:out value="${message}" /></p></c:if>
	<p class="hint">피드에 보이는 글을 최신순으로 보여 줍니다. 번호를 누르면 글 화면이, 사진을 누르면 큰 사진이 새 탭으로 열립니다.</p>

	<%-- 검토할 사진 (JM-80): AI가 음식이 아니라고 봐서 피드와 글 화면에서 사진을 가린 글. 첫 쪽에, 하나라도 있을 때만 --%>
	<c:if test="${not empty held}">
		<section class="admin-review">
			<h2>검토할 사진 ${held.size()}개</h2>
			<p class="hint">AI가 음식이 아니라고 본 글입니다. 피드와 글 화면에는 사진 대신 "관리자가 사진을 검토 중입니다"가 보입니다. 올리면 사진이 보이고, 삭제하면 글이 사라집니다.</p>
			<ul class="admin-list">
				<c:set var="rowPrefix" value="held-" />
				<c:forEach var="post" items="${held}">
					<%@ include file="/WEB-INF/views/admin/post-row.jspf" %>
				</c:forEach>
			</ul>
		</section>
		<h2 class="admin-all">전체 글</h2>
	</c:if>

	<c:choose>
		<c:when test="${empty feed.posts}">
			<p class="hint">글이 없습니다.</p>
		</c:when>
		<c:otherwise>
			<ul class="admin-list">
				<c:set var="rowPrefix" value="" />
				<c:forEach var="post" items="${feed.posts}">
					<%@ include file="/WEB-INF/views/admin/post-row.jspf" %>
				</c:forEach>
			</ul>
		</c:otherwise>
	</c:choose>

	<c:set var="nav" value="${feed.nav}" />
	<c:set var="pagerBase" value="/admin/posts?page=" />
	<%@ include file="/WEB-INF/views/common/pager.jspf" %>
</main>
</body>
</html>
