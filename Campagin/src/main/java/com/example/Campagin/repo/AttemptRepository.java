package com.example.Campagin.repo;
import com.example.Campagin.model.Attempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;


@Repository
public interface AttemptRepository extends JpaRepository<Attempt, Long> {
    List<Attempt> findByCustomerSessionIdIn(Set<String> customerSessionIds);
    List<Attempt> findByStatusFalse();

    @Modifying
    @Query("DELETE FROM Attempt a WHERE a.createdAt < :time")
    int deleteByCreatedAtBefore(LocalDateTime time);
}