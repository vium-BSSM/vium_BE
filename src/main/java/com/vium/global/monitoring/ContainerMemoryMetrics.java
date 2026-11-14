package com.vium.global.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.BaseUnits;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToDoubleFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "metrics.container-memory.enabled", havingValue = "true", matchIfMissing = false)
@Slf4j
public class ContainerMemoryMetrics implements MeterBinder {
	private final Path cgroupRoot;
	private final AtomicBoolean readFailureLogged = new AtomicBoolean();

	@Autowired
	public ContainerMemoryMetrics() {
		this(Path.of("/sys/fs/cgroup"));
	}

	ContainerMemoryMetrics(Path cgroupRoot) {
		this.cgroupRoot = cgroupRoot;
	}

	@Override
	public void bindTo(MeterRegistry registry) {
		if (!Files.isReadable(cgroupRoot.resolve("memory.current"))
				|| !Files.isReadable(cgroupRoot.resolve("memory.max"))) {
			log.info("컨테이너 메모리 메트릭 비활성화: cgroup v2 파일 없음");
			return;
		}
		String limit;
		try {
			limit = Files.readString(cgroupRoot.resolve("memory.max")).strip();
			if (!"max".equals(limit)) {
				parseBytes(limit);
			}
		} catch (IOException | IllegalArgumentException e) {
			logReadFailure();
			return;
		}
		register(registry, "working.set", "Container memory current minus inactive file cache",
			ContainerMemoryMetrics::workingSetBytes);
		if (!"max".equals(limit)) {
			register(registry, "limit", "Container RAM limit", metrics -> metrics.readBytes("memory.max"));
		}
		if (Files.isReadable(cgroupRoot.resolve("memory.swap.current"))) {
			register(registry, "swap", "Container swap usage", metrics -> metrics.readBytes("memory.swap.current"));
		}
		log.info("컨테이너 메모리 메트릭 활성화: limit={}", limit);
	}

	private void register(MeterRegistry registry, String suffix, String description,
			ToDoubleFunction<ContainerMemoryMetrics> value) {
		Gauge.builder("vium.container.memory." + suffix, this, value)
			.baseUnit(BaseUnits.BYTES).description(description).register(registry);
	}

	private double workingSetBytes() {
		try {
			long current = parseBytes(Files.readString(cgroupRoot.resolve("memory.current")).strip());
			long inactive = 0;
			for (String line : Files.readAllLines(cgroupRoot.resolve("memory.stat"))) {
				String[] fields = line.strip().split("\\s+");
				if (fields[0].equals("inactive_file")) {
					if (fields.length != 2) {
						throw new IllegalArgumentException("Invalid inactive_file");
					}
					inactive = parseBytes(fields[1]);
					break;
				}
			}
			return Math.max(0L, current - inactive);
		} catch (IOException | IllegalArgumentException e) {
			logReadFailure();
			return Double.NaN;
		}
	}

	private double readBytes(String file) {
		try {
			return parseBytes(Files.readString(cgroupRoot.resolve(file)).strip());
		} catch (IOException | IllegalArgumentException e) {
			logReadFailure();
			return Double.NaN;
		}
	}

	private static long parseBytes(String value) {
		long bytes = Long.parseLong(value);
		if (bytes < 0) {
			throw new IllegalArgumentException("Negative cgroup byte count");
		}
		return bytes;
	}

	private void logReadFailure() {
		if (readFailureLogged.compareAndSet(false, true)) {
			log.warn("컨테이너 메모리 메트릭 읽기 실패: cgroup v2 파일 확인 필요");
		}
	}
}
