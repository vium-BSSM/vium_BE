package com.vium.image;

import static org.assertj.core.api.Assertions.*;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class ImageMigrationTest {
	@Test void migrationCreatesDurableMetadataAndEnforcesConstraints() throws Exception {
		try (var connection = DriverManager.getConnection("jdbc:h2:mem:image-migration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")) {
			var sql = connection.createStatement();
			sql.execute("create table users (id bigint primary key)");
			sql.execute("insert into users values (42)");
			ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V7__add_uploaded_images.sql"));
			String insert = "insert into uploaded_images (user_id,purpose,bucket,object_key,content_type,size_bytes,created_at) "
				+ "values (42,'RECEIPT','private','images/42/a.png','image/png',100,CURRENT_TIMESTAMP)";
			sql.execute(insert);
			try (var row = sql.executeQuery("select ready from uploaded_images")) {
				assertThat(row.next()).isTrue(); assertThat(row.getBoolean(1)).isFalse();
			}
			assertThatThrownBy(() -> sql.execute(insert)).isInstanceOf(SQLException.class);
			assertThatThrownBy(() -> sql.execute(insert.replace("42,'RECEIPT'", "43,'RECEIPT'").replace("a.png", "b.png"))).isInstanceOf(SQLException.class);
			assertThatThrownBy(() -> sql.execute(insert.replace("'RECEIPT'", "'INVALID'").replace("a.png", "c.png"))).isInstanceOf(SQLException.class);
			assertThatThrownBy(() -> sql.execute(insert.replace(",100,", ",5242881,").replace("a.png", "d.png"))).isInstanceOf(SQLException.class);
		}
	}
}
