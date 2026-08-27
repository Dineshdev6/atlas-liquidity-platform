package com.atlas.liquidity.position.projection;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Read and write access to the projection. */
public interface AccountPositionJpaRepository extends JpaRepository<AccountPositionEntity, String> {

    List<AccountPositionEntity> findAllByOrderByAccountIdAsc();
}
