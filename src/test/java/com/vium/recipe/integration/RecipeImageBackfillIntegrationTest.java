package com.vium.recipe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vium.recipe.client.ImageSearchClient;
import com.vium.recipe.dto.ImageSearchResult;
import com.vium.recipe.service.RecipeImageBackfillService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:recipe-image-backfill;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class RecipeImageBackfillIntegrationTest {
	@Autowired private JdbcTemplate jdbc;
	@Autowired private RecipeImageBackfillService service;
	@MockitoBean private ImageSearchClient images;

	@BeforeEach
	void setUp() {
		for (String table : List.of("recipe_suggestions", "recipe_steps", "recipe_ingredients", "recipes")) {
			jdbc.update("delete from " + table);
		}
	}

	@Test
	void fillsMissingImageUsingStoredKeyword() {
		long id = insertRecipe("닭볶음탕", "KOREAN", "spicy braised chicken", null);
		when(images.searchImage("spicy braised chicken")).thenReturn(image("https://img/1"));

		assertThat(service.backfillMissingImages(10)).isEqualTo(1);

		assertThat(row(id)).containsEntry("image_url", "https://img/1")
			.containsEntry("image_author_name", "Author")
			.containsEntry("image_author_url", "https://unsplash.com/@author");
		verify(images).recordDownload("https://download/1");
	}

	@Test
	void fallsBackToTitleThenCategoryKeyword() {
		long id = insertRecipe("두부 덮밥", "JAPANESE", null, null);
		when(images.searchImage("두부 덮밥")).thenReturn(null);
		when(images.searchImage("japanese food")).thenReturn(image("https://img/2"));

		assertThat(service.backfillMissingImages(10)).isEqualTo(1);

		assertThat(row(id)).containsEntry("image_url", "https://img/2");
	}

	@Test
	void leavesRecipeEmptyWhenSearchFailsAndSkipsRecipesWithImages() {
		long missing = insertRecipe("실패", "KOREAN", "fail", null);
		insertRecipe("이미 있음", "KOREAN", "existing", "https://existing");
		when(images.searchImage(anyString())).thenReturn(null);

		assertThat(service.backfillMissingImages(10)).isZero();

		assertThat(row(missing).get("image_url")).isNull();
		verify(images, never()).searchImage("existing");
	}

	@Test
	void processesAtMostBatchSizeNewestFirst() {
		long older = insertRecipe("old", "KOREAN", "old", null);
		long newer = insertRecipe("new", "KOREAN", "new", null);
		when(images.searchImage(anyString())).thenReturn(image("https://img/3"));

		assertThat(service.backfillMissingImages(1)).isEqualTo(1);

		assertThat(row(newer).get("image_url")).isEqualTo("https://img/3");
		assertThat(row(older).get("image_url")).isNull();
	}

	private long insertRecipe(String title, String category, String keyword, String imageUrl) {
		jdbc.update("""
			insert into recipes (title, category, cook_time, image_url, image_keyword, source, created_at, detail_status)
			values (?, ?, 10, ?, ?, 'AI', CURRENT_TIMESTAMP, 'BASIC')
			""", title, category, imageUrl, keyword);
		return jdbc.queryForObject("select max(id) from recipes", Long.class);
	}

	private Map<String, Object> row(long id) {
		return jdbc.queryForMap("select image_url, image_author_name, image_author_url from recipes where id = ?", id);
	}

	private ImageSearchResult image(String url) {
		return new ImageSearchResult(url, "Author", "https://unsplash.com/@author",
			url.replace("img", "download"));
	}
}
