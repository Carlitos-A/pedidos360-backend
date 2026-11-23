package com.pedidos360.audit.repository;

import com.pedidos360.audit.model.RejectedKafkaEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RejectedKafkaEventRepository extends JpaRepository<RejectedKafkaEvent, Long> {
    boolean existsByDltTopicAndDltPartitionAndDltOffset(String dltTopic, int dltPartition, long dltOffset);
}
