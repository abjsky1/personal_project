package backend.visit.web;

import java.net.URI;
import java.net.URISyntaxException;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.WebUtils;

import backend.admin.auth.AdminAuthService;
import backend.common.web.Visitors;
import backend.visit.model.entity.ClientApp;
import backend.visit.model.entity.Device;
import backend.visit.model.entity.PageView;
import backend.visit.model.entity.Screen;
import backend.visit.model.vo.VisitEntry;
import backend.visit.service.PageFlowRecorder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 사용자 흐름(JM-92): 사람이 받은 화면과 글 올리기를 방문 단위로 한 줄씩 남긴다 (page_views 테이블, 기획서 12절)
// 방문 수(VisitCountInterceptor, JM-86) 바로 옆에 붙는다: 같은 화면 범위(VisitConfig), 같은 사람 판정(Visitors.isPerson),
// 화면을 다 그린 뒤(afterCompletion)에 줄을 만든다. 저장은 PageFlowRecorder가 화면 응답 밖에서 한다
// 남기지 않는 것: bot, 관리자로 들어온 세션, 에러 화면, 그리다 실패한 화면, 세션이 없는 요청,
// 이 방문이 올리거나 쓰거나 고친 글의 번호 (postHandle이 기억해 둔다)
@Slf4j
@Component
@RequiredArgsConstructor
public class PageFlowInterceptor implements HandlerInterceptor {

	// 세션에 두는 "이 방문의 번호와 줄 수와 쓰거나 고친 글" (PageFlowVisit)
	static final String VISIT = "pageflow.visit";

	private final PageFlowRecorder pageFlowRecorder;

	// 글 올리기, 댓글, 확정, 메모 고치기, 피드백 쓰기처럼 POST가 어떤 글로 넘어가면(redirect:/posts/8) 그 글을 이 방문에 기억해 둔다.
	// 응답을 보내기 전(postHandle)에 해야 한다: 넘어가는 응답이 나가자마자 브라우저가 새 글 화면을 부르기 때문
	@Override
	public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) {
		try {
			Screen.Match acted = actedOn(request, modelAndView);
			if (acted == null || !Visitors.isPerson(request.getHeader(HttpHeaders.USER_AGENT))) {
				return;
			}
			PageFlowVisit visit = visitOf(request.getSession(false));
			if (visit != null) {
				visit.rememberActed(acted);
			}
		} catch (RuntimeException e) {
			log.warn("Page flow skipped: {}", e.getClass().getSimpleName());
		}
	}

	// 여기서 예외가 밖으로 나가면 Spring이 ERROR 로그를 찍어 에러 알림 메일(기획서 11절)이 울린다. 화면은 이미 나갔으므로
	// 무슨 일이 있어도 이 한 줄만 건너뛰고 경고 로그만 남긴다 (예외 이름만. 주소와 User-Agent는 남기지 않는다)
	// ex: 화면을 그리다 난 예외. 있으면 사용자는 에러 화면을 봤으므로 남기지 않는다
	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
		if (ex != null) {
			return;
		}
		try {
			recordIfPage(request, response);
		} catch (RuntimeException e) {
			log.warn("Page flow skipped: {}", e.getClass().getSimpleName());
		}
	}

	private void recordIfPage(HttpServletRequest request, HttpServletResponse response) {
		String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
		if (!Visitors.isPerson(userAgent)) {
			return;
		}
		Screen.Match screen = screenOf(request, response);
		if (screen == null) {
			return;
		}
		PageFlowVisit visit = visitOf(request.getSession(false));
		if (visit == null) {
			return;
		}

		// Referer: 브라우저가 보내는 "이 요청을 보낸 화면의 주소". 이 사이트 안이면 그 경로로 바로 앞 화면을 찾고,
		// 다른 사이트면 도메인만 들어온 곳으로 쓴다 (브라우저는 다른 사이트로 갈 때 보통 도메인까지만 보낸다)
		URI from = refererOf(request.getHeader(HttpHeaders.REFERER));
		boolean inside = from != null && request.getServerName().equalsIgnoreCase(from.getHost());
		Screen before = inside ? previousScreenOf(pathOf(from)) : null;
		String outsideHost = from == null || inside ? null : from.getHost();

		// 줄의 값을 먼저 다 만들고 나서 순서 번호를 받는다: 번호만 쓰고 줄을 못 만들면 그 방문의 첫 줄(들어온 곳)이 사라진다
		Long target = visit.acted(screen) ? null : targetOf(request, screen);
		VisitEntry entry = entryOf(request, userAgent, outsideHost);
		Device device = Device.of(userAgent);

		int seq = visit.nextSeq();
		pageFlowRecorder.record(new PageView(visit.key(), seq, screen.screen(), target, before, device, seq == 1 ? entry : null));
	}

	// 남길 줄: 글 올리기(POST /posts가 새 글로 넘어감), 또는 화면(GET, 200, HTML)이면서 Screen에 있는 주소. 그 밖이면 null
	static Screen.Match screenOf(HttpServletRequest request, HttpServletResponse response) {
		if (VisitCountInterceptor.isUpload(request, response)) {
			return new Screen.Match(Screen.UPLOAD, null);
		}
		return VisitCountInterceptor.isPage(request, response) ? Screen.match(request.getRequestURI()) : null;
	}

	// POST가 넘어가는 글 (예: redirect:/posts/8#comments → 글 8). 번호가 있는 화면으로 넘어갈 때만
	static Screen.Match actedOn(HttpServletRequest request, ModelAndView modelAndView) {
		if (!"POST".equals(request.getMethod()) || modelAndView == null) {
			return null;
		}
		String view = modelAndView.getViewName();
		if (view == null || !view.startsWith("redirect:")) {
			return null;
		}
		URI target = refererOf(view.substring("redirect:".length()));
		Screen.Match match = target == null ? null : Screen.match(pathOf(target));
		return match != null && match.id() != null ? match : null;
	}

	// 이 세션의 방문. 처음이면 무작위 번호로 시작해 세션에 둔다. 관리자로 들어온 적이 있는 세션이면 null
	// (로그아웃해도 그 세션이 끝날 때까지. AdminAuthService.isAdminSession)
	// WebUtils.getSessionMutex: 세션마다 하나인 잠금 객체. 같은 세션의 요청이 동시에 와도 방문을 두 번 시작하지 않는다
	static PageFlowVisit visitOf(HttpSession session) {
		if (session == null) {
			return null;
		}
		try {
			if (AdminAuthService.isAdminSession(session)) {
				return null;
			}
			synchronized (WebUtils.getSessionMutex(session)) {
				if (session.getAttribute(VISIT) instanceof PageFlowVisit saved) {
					return saved;
				}
				PageFlowVisit visit = PageFlowVisit.start();
				session.setAttribute(VISIT, visit);
				return visit;
			}
		} catch (IllegalStateException e) {
			// 이 요청 안에서 세션이 끝난 경우 (남기지 않는다)
			return null;
		}
	}

	// 바로 앞 화면. /posts는 GET 화면이 없고, 올리기의 입력이 틀려 글쓰기 화면을 다시 보여 줄 때의 주소다 (POST /posts)
	static Screen previousScreenOf(String path) {
		if ("/posts".equals(path)) {
			return Screen.POST_NEW;
		}
		Screen.Match match = Screen.match(path);
		return match == null ? null : match.screen();
	}

	// 피드는 요청한 쪽 번호 (없거나 비면 1쪽, 0이나 음수도 1쪽. 마지막 쪽보다 큰 번호는 화면이 마지막 쪽을 보여 줘도 요청한 번호 그대로).
	// 글 상세와 공지 글과 피드백 글은 주소의 번호
	static Long targetOf(HttpServletRequest request, Screen.Match screen) {
		if (screen.screen() != Screen.FEED) {
			return screen.id();
		}
		String page = parameterOf(request, "page");
		if (page == null || page.isBlank()) {
			return 1L;
		}
		try {
			return (long) Math.max(1, Integer.parseInt(page.strip()));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	// 방문의 첫 줄에 남길 들어온 곳. utm 값과 링크 표시는 주소(GET)에서만 읽는다 (글 올리기 form의 칸은 보지 않는다)
	static VisitEntry entryOf(HttpServletRequest request, String userAgent, String outsideHost) {
		boolean get = "GET".equals(request.getMethod());
		return new VisitEntry(ClientApp.of(userAgent), outsideHost,
				get ? parameterOf(request, "utm_source") : null,
				get ? parameterOf(request, "utm_medium") : null,
				get ? parameterOf(request, "utm_campaign") : null,
				get ? clickIdOf(request) : null);
	}

	// 주소에 붙은 링크 표시 가운데 처음 있는 것의 이름 (값은 보지 않는다)
	private static String clickIdOf(HttpServletRequest request) {
		for (String name : VisitEntry.CLICK_IDS) {
			if (parameterOf(request, name) != null) {
				return name;
			}
		}
		return null;
	}

	// 주소의 값 하나. 값의 글자가 깨져 있으면(잘리다 만 %ED%95 같은 것) Tomcat이 처음 읽을 때 예외를 던지므로 없는 것으로 본다.
	// 그런 주소로 들어와도 줄은 남는다 (2026-11-20 검토에서 찾음: 예전에는 첫 줄을 통째로 잃었다)
	private static String parameterOf(HttpServletRequest request, String name) {
		try {
			return request.getParameter(name);
		} catch (RuntimeException e) {
			return null;
		}
	}

	// 잘못된 모양의 Referer는 없는 것으로 본다 (아무 값이나 보낼 수 있는 머리말이다)
	static URI refererOf(String referer) {
		if (referer == null || referer.isBlank()) {
			return null;
		}
		try {
			return new URI(referer.strip());
		} catch (URISyntaxException e) {
			return null;
		}
	}

	// https://jjikmuk.com 처럼 경로가 비어 있으면 첫 화면(/)이다
	private static String pathOf(URI uri) {
		String path = uri.getRawPath();
		return path == null || path.isEmpty() ? "/" : path;
	}
}
