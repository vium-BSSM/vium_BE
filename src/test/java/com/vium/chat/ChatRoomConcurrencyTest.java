package com.vium.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.vium.chat.dto.CreateChatRoomResponse;
import com.vium.chat.service.ChatRoomService;
import com.vium.post.entity.Post;
import com.vium.post.entity.PostStatus;
import com.vium.post.entity.PostType;
import com.vium.post.repository.PostRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 동시 방 생성은 실제로 겹치는 두 트랜잭션이 필요하므로 클래스 전체를 하나의 트랜잭션으로 감싸는
 * @Transactional을 쓰지 않는다(다른 스레드가 메인 스레드의 미커밋 데이터를 볼 수 없다). 대신 직접 정리한다.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:chat-room-concurrency-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class ChatRoomConcurrencyTest {

	private static final Long BUYER_ID = 42L;
	private static final Long SELLER_ID = 43L;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PostRepository postRepository;

	@Autowired
	private ChatRoomService chatRoomService;

	@Autowired
	private Clock clock;

	@Test
	void concurrentCreateResultsInOneRoomAndOneCreatedTrue() throws Exception {
		LocalDateTime now = LocalDateTime.now(clock);
		Post post = postRepository.save(Post.builder()
			.authorId(SELLER_ID)
			.inventoryItemId(1L)
			.type(PostType.SHARE)
			.status(PostStatus.OPEN)
			.title("우유 1L 팔아요")
			.description("설명")
			.price(0)
			.isOpened(true)
			.latitude(37.5)
			.longitude(127.0)
			.createdAt(now)
			.updatedAt(now)
			.build());

		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		List<CreateChatRoomResponse> results;
		try {
			Callable<CreateChatRoomResponse> task = () -> {
				ready.countDown();
				start.await();
				return chatRoomService.create(BUYER_ID, post.getId());
			};
			Future<CreateChatRoomResponse> first = executor.submit(task);
			Future<CreateChatRoomResponse> second = executor.submit(task);

			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
		} finally {
			executor.shutdown();
			executor.awaitTermination(10, TimeUnit.SECONDS);
		}

		try {
			long createdTrueCount = results.stream().filter(CreateChatRoomResponse::created).count();
			assertThat(createdTrueCount).isEqualTo(1);
			assertThat(results.get(0).chatRoomId()).isEqualTo(results.get(1).chatRoomId());

			Integer roomCount = jdbcTemplate.queryForObject(
				"select count(*) from chat_rooms where post_id = ? and buyer_id = ?", Integer.class, post.getId(),
				BUYER_ID);
			assertThat(roomCount).isEqualTo(1);

			Integer participantCount = jdbcTemplate.queryForObject(
				"select count(*) from chat_participants where chat_room_id = ?", Integer.class,
				results.get(0).chatRoomId());
			assertThat(participantCount).isEqualTo(2);
		} finally {
			jdbcTemplate.update("delete from chat_participants where chat_room_id = ?", results.get(0).chatRoomId());
			jdbcTemplate.update("delete from chat_rooms where id = ?", results.get(0).chatRoomId());
			jdbcTemplate.update("delete from posts where id = ?", post.getId());
		}
	}
}
