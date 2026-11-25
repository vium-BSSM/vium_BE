package com.vium.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.vium.notification.service.ExpiryPushScheduler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.StreamUtils;

/**
 * push.expiry.enabled=true && fcm.enabled=true(정상 조합)일 때의 동작을 검증하기 위해
 * 별도 Spring 컨텍스트로 기동한다. fcm.credentials-json은 실제 서비스 계정이 아니라,
 * 테스트 전용으로 생성한 키를 담은 픽스처(src/test/resources/fcm/fake-service-account.json)를 쓴다.
 * 이 키로는 실제 FCM 전송이 불가능하고, 이 테스트는 빈 생성(문법적으로 유효한 자격 증명 파싱)까지만 확인한다.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:expiry-push-scheduler-enabled-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
	"push.expiry.enabled=true",
	"push.expiry.cron=0 0 9 * * *",
	"push.expiry.time-zone=Asia/Seoul",
	"fcm.enabled=true"
})
class ExpiryPushSchedulerEnabledTest {

	@DynamicPropertySource
	static void fcmCredentials(DynamicPropertyRegistry registry) {
		registry.add("fcm.credentials-json", ExpiryPushSchedulerEnabledTest::readFakeCredentials);
	}

	private static String readFakeCredentials() {
		try {
			return StreamUtils.copyToString(
				new ClassPathResource("fcm/fake-service-account.json").getInputStream(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Autowired
	private ExpiryPushScheduler expiryPushScheduler;

	@Autowired
	private ScheduledTaskHolder scheduledTaskHolder;

	@Test
	void expiryPushSchedulerBeanExistsWhenEnabled() {
		assertThat(expiryPushScheduler).isNotNull();
	}

	@Test
	void scheduledCronTaskIsRegisteredWhenEnabled() {
		assertThat(scheduledTaskHolder.getScheduledTasks().stream()
			.filter(task -> task.getTask().toString().contains("ExpiryPushScheduler"))
			.map(task -> ((CronTask) task.getTask()).getExpression()))
			.containsExactly("0 0 9 * * *");
	}

	@AfterAll
	static void logsConfiguredScheduleOnce(CapturedOutput output) {
		assertThat(output.getOut())
			.containsOnlyOnce("소비기한 임박 푸시 스케줄러 활성화: cron=0 0 9 * * *, zone=Asia/Seoul, fcm=true");
	}
}
