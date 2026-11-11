<%-- 공지 상세 (JM-65): [안내] 제목, 날짜와 조회수, 본문 (문단과 점 목록) --%>
<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title><c:out value="${notice.title}" /> - 찍먹</title>
<link rel="stylesheet" href="<c:url value='/css/app.css' />">
</head>
<body>
<main>
	<header class="top">
		<a href="/notice" data-back>← 공지 목록</a>
	</header>

	<article class="notice-detail">
		<h1>[<c:out value="${notice.category}" />] <c:out value="${notice.title}" /></h1>
		<p class="notice-detail-meta"><span>${notice.date}</span><span>조회 <fmt:formatNumber value="${notice.views}" /></span></p>

		<%-- 본문: notices.json의 줄들. "- "로 시작한 줄은 점 목록으로 묶여 온다 (NoticeCatalog.toBlocks) --%>
		<div class="notice-body">
			<c:forEach var="block" items="${notice.blocks}">
				<c:choose>
					<c:when test="${not empty block.items}">
						<ul>
							<c:forEach var="item" items="${block.items}">
								<li><c:out value="${item}" /></li>
							</c:forEach>
						</ul>
					</c:when>
					<c:otherwise>
						<p><c:out value="${block.text}" /></p>
					</c:otherwise>
				</c:choose>
			</c:forEach>
		</div>

		<a class="notice-back" href="/notice" data-back>목록으로</a>
	</article>

	<%@ include file="/WEB-INF/views/common/footer.jspf" %>
</main>
</body>
</html>
