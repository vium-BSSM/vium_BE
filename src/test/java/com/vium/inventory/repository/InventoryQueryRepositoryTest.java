package com.vium.inventory.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vium.inventory.dto.IngredientListResponse;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class InventoryQueryRepositoryTest {
	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"0.00", "2500.00", "9999999999.00"})
	void readsNumericAmountWithoutUnsupportedTypedGetObject(String value) throws Exception {
		var resultSet = mock(ResultSet.class);
		BigDecimal amount = value == null ? null : new BigDecimal(value);
		when(resultSet.getBigDecimal("amount")).thenReturn(amount);
		var repository = repositoryWithRow(resultSet);

		var items = repository.findActiveIngredients(1L, null);

		assertThat(items).singleElement().satisfies(item ->
			assertThat(item.amount()).isEqualTo(amount == null ? null : amount.longValueExact()));
		verify(resultSet).getBigDecimal("amount");
		verify(resultSet, never()).getObject("amount", Long.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"2500.50", "9223372036854775808"})
	void doesNotSilentlyTruncateOrOverflowAmount(String value) throws Exception {
		var resultSet = mock(ResultSet.class);
		when(resultSet.getBigDecimal("amount")).thenReturn(new BigDecimal(value));
		var repository = repositoryWithRow(resultSet);

		assertThatThrownBy(() -> repository.findActiveIngredients(1L, null))
			.isInstanceOf(ArithmeticException.class);
	}

	private InventoryQueryRepository repositoryWithRow(ResultSet resultSet) throws Exception {
		var jdbcTemplate = mock(JdbcTemplate.class);
		// Match pgjdbc's rejection of numeric -> Long, including SQL NULL.
		when(resultSet.getObject("amount", Long.class))
			.thenThrow(new SQLException("conversion to class java.lang.Long from numeric not supported"));
		when(jdbcTemplate.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<IngredientListResponse.Item>>any(),
				any(Object[].class))).thenAnswer(invocation -> {
			RowMapper<IngredientListResponse.Item> mapper = invocation.getArgument(1);
			return List.of(mapper.mapRow(resultSet, 0));
		});
		return new InventoryQueryRepository(jdbcTemplate);
	}
}
