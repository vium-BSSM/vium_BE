package com.vium.safety.repository;

import com.vium.safety.entity.UserBlock;
import com.vium.safety.entity.UserBlockId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserBlockRepository extends JpaRepository<UserBlock, UserBlockId> {

	@Query("""
		select count(b) > 0 from UserBlock b
		where (b.blockerId = :userA and b.blockedId = :userB)
		   or (b.blockerId = :userB and b.blockedId = :userA)
		""")
	boolean existsBetween(@Param("userA") Long userA, @Param("userB") Long userB);

	@Query("select b.blockedId from UserBlock b where b.blockerId = :userId")
	List<Long> findBlockedIdsByBlockerId(@Param("userId") Long userId);

	@Query("select b.blockerId from UserBlock b where b.blockedId = :userId")
	List<Long> findBlockerIdsByBlockedId(@Param("userId") Long userId);

	boolean existsByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

	void deleteByBlockerIdAndBlockedId(Long blockerId, Long blockedId);
}
