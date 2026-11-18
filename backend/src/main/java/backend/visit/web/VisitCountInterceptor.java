// 방문 통계(JM-86)를 세는 웹 코드를 두는 패키지
package backend.visit.web;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import backend.common.web.Visitors;
import backend.visit.model.entity.VisitMetric;
import backend.visit.service.VisitCounter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;

// 사람의 방문과 글 올리기를 시/도별 하루 합계로 센다 (JM-86, 운영자 통계의 "방문 지역")
// afterCompletion: 화면을 다 보낸 뒤에 실행된다 → 응답 상태와 세션이 정해진 뒤에 보고, 세는 일이 화면을 늦추지 않는다
// 검색 엔진, 미리보기, 점검 스크립트(Visitors.isPerson)는 세지 않는다. 운영자 화면, API, 정적 파일은 VisitConfig가 뺀다
@Component
@RequiredArgsConstructor
public class VisitCountInterceptor implements HandlerInterceptor {

	private final VisitCounter visitCounter;

	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
		if (!Visitors.isPerson(request.getHeader("User-Agent"))) {
			return;
		}
		if (isNewVisit(request, response)) {
			visitCounter.count(VisitMetric.VISIT, request.getRemoteAddr());
		} else if (isUpload(request, response)) {
			visitCounter.count(VisitMetric.UPLOAD, request.getRemoteAddr());
		}
	}

	// 방문: 화면(HTML)을 200으로 받았고, 이 요청에서 세션이 새로 생겼다 = 이 브라우저가 들어온 첫 화면.
	// 세션은 화면(JSP)을 그릴 때 생기고 30분 동안 쓰지 않으면 사라지므로, 30분 넘게 쉬었다가 오면 다시 한 번 센다.
	// 세션은 원래 쓰던 것(JSESSIONID)이고, 세려고 쿠키를 새로 만들지 않는다
	static boolean isNewVisit(HttpServletRequest request, HttpServletResponse response) {
		HttpSession session = request.getSession(false);
		String contentType = response.getContentType();
		return "GET".equals(request.getMethod()) && response.getStatus() == HttpServletResponse.SC_OK
				&& contentType != null && contentType.startsWith("text/html") && session != null && session.isNew();
	}

	// 글 올리기: POST /posts가 새 글 화면으로 넘어갔다 (302). 막히거나(429) 잘못된 사진(400)은 세지 않는다
	static boolean isUpload(HttpServletRequest request, HttpServletResponse response) {
		return "POST".equals(request.getMethod()) && "/posts".equals(request.getRequestURI())
				&& response.getStatus() == HttpServletResponse.SC_FOUND;
	}
}
