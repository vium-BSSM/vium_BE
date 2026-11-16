package com.vium.trade;

import static org.assertj.core.api.Assertions.assertThat;

import com.vium.trade.service.TradeAutoCompleteScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:trade-scheduler-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
	"trade.auto-complete.enabled=true", "trade.auto-complete.cron=0 15 4 * * *"
})
class TradeAutoCompleteSchedulerEnabledTest {
	@Autowired TradeAutoCompleteScheduler scheduler;
	@Autowired ScheduledTaskHolder tasks;

	@Test
	void registersConfiguredScheduleWhenEnabled() {
		assertThat(scheduler).isNotNull();
		assertThat(tasks.getScheduledTasks().stream()
			.filter(task -> task.getTask().toString().contains("TradeAutoCompleteScheduler"))
			.map(task -> ((CronTask) task.getTask()).getExpression())).containsExactly("0 15 4 * * *");
	}
}
