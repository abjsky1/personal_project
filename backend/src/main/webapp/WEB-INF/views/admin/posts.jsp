<%-- 관리자 글 목록 (JM-82): 피드에 보이는 글(지우지 않은 글)을 최신순으로. 번호, 사진, 닉네임, 시각, 숫자, AI 결과를 보고 지운다 --%>
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
	<header class="admin-top">
		<h1>찍먹 관리자</h1>
		<nav class="admin-nav">
			<a href="/admin/posts" class="active" aria-current="page">글 목록</a>
			<a href="/" target="_blank" rel="noopener">사이트 열기</a>
		</nav>
		<form method="post" action="/admin/logout" class="admin-logout">
			<input type="hidden" name="_csrf" value="${csrf}">
			<button type="submit">로그아웃</button>
		</form>
	</header>

	<c:if test="${not empty message}"><p class="admin-flash"><c:out value="${message}" /></p></c:if>
	<p class="hint">피드에 보이는 글을 최신순으로 보여 줍니다. 번호를 누르면 글 화면이, 사진을 누르면 큰 사진이 새 탭으로 열립니다.</p>

	<c:choose>
		<c:when test="${empty feed.posts}">
			<p class="hint">글이 없습니다.</p>
		</c:when>
		<c:otherwise>
			<ul class="admin-list">
				<c:forEach var="post" items="${feed.posts}">
					<li class="admin-row" id="post-${post.id}">
						<a class="admin-thumb" href="${post.imageUrl}" target="_blank" rel="noopener">
							<img src="${post.thumbnailUrl}" alt="${post.id}번 글의 사진" loading="lazy">
						</a>
						<div class="admin-info">
							<p class="admin-head">
								<a class="admin-no" href="/posts/${post.id}" target="_blank" rel="noopener">${post.id}번</a>
								<strong><c:out value="${post.nickname}" /></strong>
							</p>
							<p class="admin-meta">
								<span><c:out value="${post.createdAt}" /></span>
								<span>조회 ${post.viewCount}</span>
								<span>좋아요 ${post.likeCount}</span>
								<span>댓글 ${post.commentCount}</span>
							</p>
							<p class="admin-meta">
								<c:choose>
									<c:when test="${post.analysisStatus == 'DONE'}"><span class="admin-ai">AI <fmt:formatNumber value="${post.totalKcal}" maxFractionDigits="0" /> kcal</span></c:when>
									<c:when test="${post.analysisStatus == 'NOT_FOOD'}"><span class="admin-ai warn">AI 음식 아님</span></c:when>
									<c:when test="${post.analysisStatus == 'FAILED'}"><span class="admin-ai warn">AI 분석 실패</span></c:when>
									<c:when test="${post.analysisStatus == 'LIMIT_EXCEEDED'}"><span class="admin-ai warn">AI 하루 상한 초과</span></c:when>
									<c:when test="${post.analysisStatus == 'PENDING'}"><span class="admin-ai">AI 분석 중</span></c:when>
									<c:otherwise><span class="admin-ai">AI 기록 없음</span></c:otherwise>
								</c:choose>
								<c:if test="${not empty post.memo}"><span class="admin-memo"><c:out value="${post.memo}" /></span></c:if>
							</p>
						</div>
						<form method="post" action="/admin/posts/${post.id}/delete" class="admin-delete"
							onsubmit="return confirm('${post.id}번 글을 지울까요?');">
							<input type="hidden" name="_csrf" value="${csrf}">
							<input type="hidden" name="page" value="${feed.nav.page}">
							<button type="submit" class="danger">삭제</button>
						</form>
					</li>
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
