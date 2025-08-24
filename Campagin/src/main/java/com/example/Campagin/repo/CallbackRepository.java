package com.example.Campagin.repo;

import com.example.Campagin.model.Callback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CallbackRepository extends JpaRepository<Callback, String> {
    List<Callback> findByConversationIdInAndOutboundContactIdIn(List<String> conversationIds, List<String> outboundContactIds);
    List<Callback> findByStatusFalse();
}