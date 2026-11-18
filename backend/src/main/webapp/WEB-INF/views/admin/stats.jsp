<%-- 관리자 통계 (JM-75): 운영자 API(/api/admin/stats)와 같은 값(AdminStatsResponse)을 서버에서 그린다. 화면이 API를 부르지 않는다.
     카드 하나가 jspf 하나다 (admin/stats/). 새 통계를 더할 때는 기획서 13절의 순서대로: StatsMapper.xml에 select 하나,
     건수 record 하나, AdminStatsResponse에 nested record 하나(맨 뒤), 여기에 카드 jspf 하나와 include 한 줄 --%>
<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<%@ taglib prefix="admin" tagdir="/WEB-INF/tags/admin" %>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>통계 - 찍먹 관리자</title>
<link rel="stylesheet" href="<c:url value='/css/app.css' />">
</head>
<body>
<main class="admin">
	<c:set var="adminTab" value="stats" />
	<%@ include file="/WEB-INF/views/admin/header.jspf" %>

	<%-- 기간: 오늘을 포함한 며칠 (한국 날짜). 글이 올라온 날을 기준으로 센다 --%>
	<nav class="stats-period" aria-label="기간">
		<a href="/admin/stats?days=1"<c:if test="${days == 1}"> class="active" aria-current="page"</c:if>>오늘</a>
		<a href="/admin/stats?days=7"<c:if test="${days == 7}"> class="active" aria-current="page"</c:if>>7일</a>
		<a href="/admin/stats?days=30"<c:if test="${days == 30}"> class="active" aria-current="page"</c:if>>30일</a>
		<a href="/admin/stats?days=90"<c:if test="${days == 90}"> class="active" aria-current="page"</c:if>>90일</a>
		<span class="stats-range">${stats.from.monthValue}월 ${stats.from.dayOfMonth}일 ~ ${stats.to.monthValue}월 ${stats.to.dayOfMonth}일 (한국 날짜)</span>
	</nav>

	<%@ include file="/WEB-INF/views/admin/stats/confirm.jspf" %>
	<%@ include file="/WEB-INF/views/admin/stats/activity.jspf" %>
	<%@ include file="/WEB-INF/views/admin/stats/analysis.jspf" %>
	<%@ include file="/WEB-INF/views/admin/stats/location.jspf" %>
	<%@ include file="/WEB-INF/views/admin/stats/visits.jspf" %>
	<%@ include file="/WEB-INF/views/admin/stats/feedback.jspf" %>
</main>
</body>
</html>
