package com.vium.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vium.auth.service.TokenService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 보완 6 실검증: (a) 실제 HTTP로 연결해 접속 직후 ping을 받는지, (b) 연결이 열려있는 동안 authClock을
 * 앞으로 돌려 "그 사이 토큰이 만료된" 상태를 만든 뒤, 서버가 sse.max-connection-age-seconds로 연결을
 * 먼저 완전히 종료하게 해 ASYNC 재디스패치가 실제로 일어나게 하고, 그 과정에서 보안 필터가 이미 커밋된
 * 스트림에 401을 쓰려다 생기는 로그 노이즈나 예외가 있는지 관찰한다. authClock(JWT 검증/발급 전용)과
 * SSE 연결의 connectedAt을 재는 업무용 clock 빈은 서로 다른 빈이라, authClock만 미래로 돌려도
 * max-connection-age 타이머 자체는 실제 벽시계 시간(1~2초)만 지나면 된다.
 * JDK HttpClient만 사용하며 새 테스트 의존성을 추가하지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.datasource.url=jdbc:h2:mem:sse-jwt-expiry-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
	"sse.heartbeat-interval-seconds=1",
	"sse.max-connection-age-seconds=1"
})
class SseJwtExpiryIntegrationTest {

	private static final Long USER_ID = 42L;
	private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private TokenService tokenService;

	@MockitoBean(name = "authClock")
	private Clock authClock;

	private ListAppender<ILoggingEvent> rootAppender;

	@BeforeEach
	void setUp() {
		when(authClock.instant()).thenReturn(T0);
		when(authClock.getZone()).thenReturn(ZoneOffset.UTC);

		rootAppender = new ListAppender<>();
		rootAppender.start();
		((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(rootAppender);
	}

	@AfterEach
	void tearDown() {
		((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(rootAppender);
	}

	private void awaitTrue(java.util.function.BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (System.currentTimeMillis() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			Thread.sleep(20);
		}
	}

	@Test
	void connectReceivesPingThenMaxAgeClosureWithExpiredTokenIsObserved() throws Exception {
		String accessToken = tokenService.issue(USER_ID).accessToken();

		HttpClient client = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/me/events"))
			.header("Authorization", "Bearer " + accessToken)
			.GET().build();
		HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
		assertThat(response.statusCode()).isEqualTo(200);

		List<String> lines = Collections.synchronizedList(new ArrayList<>());
		AtomicBoolean streamClosed = new AtomicBoolean(false);
		Thread reader = new Thread(() -> {
			try (BufferedReader br = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = br.readLine()) != null) {
					lines.add(line);
				}
			} catch (IOException e) {
				lines.add("READER_EXCEPTION:" + e);
			} finally {
				streamClosed.set(true);
			}
		});
		reader.setDaemon(true);
		reader.start();

		awaitTrue(() -> lines.stream().anyMatch(l -> l.contains("event:ping")), 2000);
		assertThat(lines.stream().anyMatch(l -> l.contains("event:ping"))).as("접속 직후 ping 수신").isTrue();

		// 그 사이 (실제로는 흐르지 않은) 시간이 흘러 토큰이 만료된 것처럼 authClock만 미래로 돌린다.
		when(authClock.instant()).thenReturn(T0.plusSeconds(3600 + 120));

		// sse.max-connection-age-seconds=1 + heartbeat=1s 틱이 실제 벽시계로 1~2초 뒤 연결을 끊는다.
		awaitTrue(streamClosed::get, 5000);

		reader.join(1000);

		List<ILoggingEvent> errorEvents = rootAppender.list.stream()
			.filter(e -> e.getLevel().isGreaterOrEqual(Level.ERROR))
			.toList();

		System.out.println("[SseJwtExpiryIntegrationTest] streamClosed=" + streamClosed.get()
			+ ", lines=" + lines + ", errorEvents=" + errorEvents.stream().map(ILoggingEvent::getFormattedMessage).toList());

		assertThat(streamClosed.get()).as("서버가 커밋된 스트림을 결국 정상적으로 닫는다").isTrue();
	}
}
