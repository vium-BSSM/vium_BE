package com.vium.post.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vium.post.dto.PostBasicInfo;
import com.vium.post.entity.Post;
import com.vium.post.entity.PostStatus;
import com.vium.post.entity.PostType;
import com.vium.post.repository.PostRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:post-reader-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
	"images.storage.public-base-url=https://images.example.com"
})
@Transactional
class PostReaderIntegrationTest {

	@Autowired
	private PostReader postReader;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Post save(PostStatus status) {
		LocalDateTime now = LocalDateTime.now();
		return postRepository.save(Post.builder()
			.authorId(7L)
			.inventoryItemId(100L)
			.type(PostType.SALE)
			.status(status)
			.title("제목")
			.description("설명")
			.price(5000)
			.isOpened(false)
			.latitude(37.5)
			.longitude(127.0)
			.createdAt(now)
			.updatedAt(now)
			.build());
	}

	@Test
	void findBasicInfoReturnsCoreFields() {
		Post post = save(PostStatus.OPEN);

		Optional<PostBasicInfo> info = postReader.findBasicInfo(post.getId());

		assertThat(info).isPresent();
		PostBasicInfo value = info.get();
		assertThat(value.postId()).isEqualTo(post.getId());
		assertThat(value.authorId()).isEqualTo(7L);
		assertThat(value.title()).isEqualTo("제목");
		assertThat(value.status()).isEqualTo("OPEN");
		assertThat(value.inventoryItemId()).isEqualTo(100L);
		assertThat(value.type()).isEqualTo("SALE");
		assertThat(value.price()).isEqualTo(5000);
		assertThat(value.thumbnailUrl()).isNull();
	}

	@Test
	void findBasicInfoResolvesThumbnailFromFirstImage() {
		Post post = save(PostStatus.OPEN);
		jdbcTemplate.update("""
			insert into uploaded_images (id, user_id, purpose, bucket, object_key, content_type,
			size_bytes, ready, created_at)
			values (1, 7, 'POST', 'public', 'images/7/thumb.jpg', 'image/jpeg', 100, true, CURRENT_TIMESTAMP)
			""");
		jdbcTemplate.update("insert into post_images (post_id, image_id, sort_order) values (?, 1, 0)", post.getId());

		Optional<PostBasicInfo> info = postReader.findBasicInfo(post.getId());

		assertThat(info).isPresent();
		assertThat(info.get().thumbnailUrl()).isEqualTo("https://images.example.com/images/7/thumb.jpg");
	}

	@Test
	void findBasicInfoIsEmptyForMissingPost() {
		assertThat(postReader.findBasicInfo(999_999L)).isEmpty();
	}

	@Test
	void findBasicInfoIsEmptyForDeletedPost() {
		Post post = save(PostStatus.OPEN);
		org.springframework.test.util.ReflectionTestUtils.setField(post, "deletedAt", LocalDateTime.now());
		postRepository.saveAndFlush(post);

		assertThat(postReader.findBasicInfo(post.getId())).isEmpty();
	}
}
