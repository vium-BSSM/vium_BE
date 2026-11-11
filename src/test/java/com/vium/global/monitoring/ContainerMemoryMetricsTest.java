package com.vium.global.monitoring;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerMemoryMetricsTest {
	private static final long MIB = 1024 * 1024;
	@TempDir Path root;
	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
	private ContainerMemoryMetrics metrics;

	@BeforeEach
	void setup() throws IOException {
		write("memory.current", Long.toString(400 * MIB));
		write("memory.max", Long.toString(512 * MIB));
		write("memory.stat", "anon 100\nfile 200\ninactive_file " + 50 * MIB + "\n");
		write("memory.swap.current", "0");
		metrics = new ContainerMemoryMetrics(root);
	}

	@Test
	void registersWorkingSetLimitAndSwapInBytes() {
		metrics.bindTo(registry);
		assertThat(value("working.set")).isEqualTo(350 * MIB);
		assertThat(value("limit")).isEqualTo(512 * MIB);
		assertThat(value("swap")).isZero();
		assertThat(registry.getMeters()).allSatisfy(meter ->
			assertThat(meter.getId().getBaseUnit()).isEqualTo("bytes"));
	}

	@Test
	void unlimitedMemoryOmitsOnlyLimit() throws IOException {
		write("memory.max", "max\n");
		metrics.bindTo(registry);
		assertThat(registry.find("vium.container.memory.limit").gauge()).isNull();
		assertThat(registry.getMeters()).hasSize(2);
	}

	@Test
	void missingSwapOmitsOnlySwap() throws IOException {
		Files.delete(root.resolve("memory.swap.current"));
		metrics.bindTo(registry);
		assertThat(registry.find("vium.container.memory.swap").gauge()).isNull();
		assertThat(registry.getMeters()).hasSize(2);
	}

	@Test
	void missingRequiredFileOmitsAllMeters() throws IOException {
		Files.delete(root.resolve("memory.current"));
		metrics.bindTo(registry);
		assertThat(registry.getMeters()).isEmpty();
	}

	@Test
	void missingLimitOmitsAllMeters() throws IOException {
		Files.delete(root.resolve("memory.max"));
		metrics.bindTo(registry);
		assertThat(registry.getMeters()).isEmpty();
	}

	@Test
	void corruptedFilesAfterBindingReturnNanAndRecover() throws IOException {
		metrics.bindTo(registry);
		for (String file : new String[]{"memory.current", "memory.max", "memory.swap.current"}) {
			write(file, "broken");
		}
		assertThat(value("working.set")).isNaN();
		assertThat(value("limit")).isNaN();
		assertThat(value("swap")).isNaN();
		write("memory.current", Long.toString(100 * MIB));
		assertThat(value("working.set")).isEqualTo(50 * MIB);
	}

	@Test
	void malformedOrMissingStatReturnsNan() throws IOException {
		metrics.bindTo(registry);
		write("memory.stat", "inactive_file invalid");
		assertThat(value("working.set")).isNaN();
		Files.delete(root.resolve("memory.stat"));
		assertThat(value("working.set")).isNaN();
	}

	@Test
	void absentInactiveFileUsesCurrentAndLargerCacheClampsToZero() throws IOException {
		metrics.bindTo(registry);
		write("memory.stat", "anon 100\n");
		assertThat(value("working.set")).isEqualTo(400 * MIB);
		write("memory.stat", "inactive_file " + 500 * MIB);
		assertThat(value("working.set")).isZero();
	}

	private void write(String file, String value) throws IOException {
		Files.writeString(root.resolve(file), value);
	}

	private double value(String suffix) {
		return registry.get("vium.container.memory." + suffix).gauge().value();
	}
}
