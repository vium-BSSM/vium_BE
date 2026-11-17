package com.vium.chat.repository;

import com.vium.chat.dto.ChatRoomListRow;
import com.vium.chat.dto.ChatRoomSummaryDto;
import com.vium.chat.dto.LastMessageDto;
import com.vium.chat.dto.PartnerDto;
import com.vium.chat.dto.PostSummaryDto;
import com.vium.chat.entity.ChatMessageType;
import com.vium.image.config.ImageStorageProperties;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 채팅방 목록을 post·users·chat_messages와 조인해 읽기 전용으로 조회한다.
 * 정렬 기준(activityAt)은 coalesce(last_message_at, created_at)이며 메시지가 없는 방도 생성 시각으로 노출한다.
 */
@Repository
@RequiredArgsConstructor
public class ChatRoomQueryRepository {

	private static final short THUMBNAIL_SORT_ORDER = 0;
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private final JdbcTemplate jdbcTemplate;
	private final ImageStorageProperties imageStorageProperties;

	public List<ChatRoomListRow> findRooms(Long userId, LocalDateTime cursorActivityAt, Long cursorRoomId, int limit) {
		StringBuilder sql = new StringBuilder("""
			select cr.id as chat_room_id,
			       p.id as post_id, p.title as post_title, p.status as post_status,
			       post_img.object_key as post_thumbnail_key,
			       pp.user_id as partner_id, u.display_name as partner_display_name,
			       partner_img.object_key as partner_image_key,
			       lm.type as last_message_type, lm.content as last_message_content,
			       lm.created_at as last_message_at,
			       coalesce(cr.last_message_at, cr.created_at) as activity_at,
			       (select count(*) from chat_messages cm2
			          where cm2.chat_room_id = cr.id
			            and cm2.id > coalesce(mp.last_read_message_id, 0)
			            and (cm2.sender_id is null or cm2.sender_id <> mp.user_id)
			       ) as unread_count
			from chat_rooms cr
			join chat_participants mp on mp.chat_room_id = cr.id and mp.user_id = ? and mp.left_at is null
			join chat_participants pp on pp.chat_room_id = cr.id and pp.user_id <> ?
			join posts p on p.id = cr.post_id
			left join post_images post_pi on post_pi.post_id = p.id and post_pi.sort_order = ?
			left join uploaded_images post_img on post_img.id = post_pi.image_id
			join users u on u.id = pp.user_id
			left join uploaded_images partner_img on partner_img.id = u.profile_image_id
			left join (
			    select chat_room_id, max(id) as last_message_id
			    from chat_messages
			    group by chat_room_id
			) lmid on lmid.chat_room_id = cr.id
			left join chat_messages lm on lm.id = lmid.last_message_id
			where 1 = 1
			""");
		List<Object> params = new ArrayList<>(List.of(userId, userId, THUMBNAIL_SORT_ORDER));
		if (cursorActivityAt != null) {
			sql.append("""
				and (coalesce(cr.last_message_at, cr.created_at) < ?
				     or (coalesce(cr.last_message_at, cr.created_at) = ? and cr.id < ?))
				""");
			Timestamp cursorTimestamp = Timestamp.valueOf(cursorActivityAt);
			params.add(cursorTimestamp);
			params.add(cursorTimestamp);
			params.add(cursorRoomId);
		}
		sql.append(" order by activity_at desc, cr.id desc limit ?");
		params.add(limit);

		return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> mapRow(rs), params.toArray());
	}

	private ChatRoomListRow mapRow(ResultSet rs) throws SQLException {
		Long chatRoomId = rs.getLong("chat_room_id");
		PostSummaryDto post = new PostSummaryDto(rs.getLong("post_id"), rs.getString("post_title"),
			buildUrl(rs.getString("post_thumbnail_key")), rs.getString("post_status"));
		PartnerDto partner = new PartnerDto(rs.getLong("partner_id"), rs.getString("partner_display_name"),
			buildUrl(rs.getString("partner_image_key")));

		LastMessageDto lastMessage = null;
		String lastMessageTypeRaw = rs.getString("last_message_type");
		if (lastMessageTypeRaw != null) {
			ChatMessageType type = ChatMessageType.valueOf(lastMessageTypeRaw);
			String preview = type == ChatMessageType.IMAGE ? "사진" : rs.getString("last_message_content");
			LocalDateTime sentAt = rs.getTimestamp("last_message_at").toLocalDateTime();
			lastMessage = new LastMessageDto(type, preview, sentAt.atZone(SEOUL).toOffsetDateTime());
		}

		int unreadCount = rs.getInt("unread_count");
		LocalDateTime activityAt = rs.getTimestamp("activity_at").toLocalDateTime();

		ChatRoomSummaryDto summary = new ChatRoomSummaryDto(chatRoomId, post, partner, lastMessage, unreadCount);
		return new ChatRoomListRow(summary, activityAt);
	}

	private String buildUrl(String objectKey) {
		if (objectKey == null) {
			return null;
		}
		return imageStorageProperties.publicBaseUrl().replaceAll("/$", "") + "/" + objectKey;
	}
}
