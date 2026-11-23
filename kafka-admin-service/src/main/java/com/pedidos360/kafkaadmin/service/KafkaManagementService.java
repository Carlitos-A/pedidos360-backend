package com.pedidos360.kafkaadmin.service;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class KafkaManagementService {
    private final Admin admin;
    private final boolean allowDelete;
    public KafkaManagementService(Admin admin, @Value("${app.kafka-admin.allow-delete:false}") boolean allowDelete) {
        this.admin = admin; this.allowDelete = allowDelete;
    }
    public record TopicSummary(String name, int partitions, int replicationFactor) {}
    public record PartitionInfo(int id, Integer leader, List<Integer> replicas, List<Integer> inSyncReplicas) {}
    public record TopicConfig(String value, String source, boolean readOnly) {}
    public record TopicDetails(String name, List<PartitionInfo> partitions, int replicationFactor,
                               String retentionMs, String cleanupPolicy, Map<String, TopicConfig> configs) {}
    public record GroupSummary(String groupId, String state) {}
    public record MemberInfo(String clientId, String host, List<String> assignments) {}
    public record GroupDetails(String groupId, String state, List<MemberInfo> members, Map<String, Long> offsets) {}

    public List<TopicSummary> topics() {
        Set<String> names = await(admin.listTopics().names());
        if (names.isEmpty()) return List.of();
        Map<String, TopicDescription> descriptions = await(admin.describeTopics(names).allTopicNames());
        return descriptions.values().stream().map(d -> new TopicSummary(d.name(), d.partitions().size(),
            d.partitions().isEmpty() ? 0 : d.partitions().getFirst().replicas().size()))
            .sorted(Comparator.comparing(TopicSummary::name)).toList();
    }

    public TopicDetails topic(String name) {
        validName(name);
        TopicDescription description = await(admin.describeTopics(List.of(name)).allTopicNames()).get(name);
        if (description == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tópico inexistente");
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, name);
        Config config = await(admin.describeConfigs(List.of(resource)).all()).get(resource);
        List<PartitionInfo> partitions = description.partitions().stream().map(p -> new PartitionInfo(p.partition(),
            p.leader() == null ? null : p.leader().id(), p.replicas().stream().map(n -> n.id()).toList(),
            p.isr().stream().map(n -> n.id()).toList())).toList();
        Map<String, TopicConfig> visibleConfigs = new TreeMap<>();
        if (config != null) config.entries().stream().filter(entry -> !entry.isSensitive())
            .forEach(entry -> visibleConfigs.put(entry.name(),
                new TopicConfig(entry.value(), entry.source().name(), entry.isReadOnly())));
        return new TopicDetails(name, partitions, partitions.isEmpty() ? 0 : partitions.getFirst().replicas().size(),
            setting(config, "retention.ms"), setting(config, "cleanup.policy"), visibleConfigs);
    }

    public TopicDetails create(String name, int partitions, int replicationFactor, long retentionMs, String cleanupPolicy) {
        validName(name); validTopology(partitions, replicationFactor); validConfig(retentionMs, cleanupPolicy);
        NewTopic topic = new NewTopic(name, partitions, (short) replicationFactor)
            .configs(Map.of("retention.ms", String.valueOf(retentionMs), "cleanup.policy", cleanupPolicy));
        await(admin.createTopics(List.of(topic)).all());
        return topic(name);
    }

    public TopicDetails update(String name, Integer partitions, Long retentionMs, String cleanupPolicy) {
        TopicDetails current = topic(name);
        if (partitions == null && retentionMs == null && cleanupPolicy == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No hay cambios");
        if (partitions != null) {
            if (partitions < current.partitions().size() || partitions > 100)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Solo se pueden aumentar particiones hasta 100");
        }
        if (retentionMs != null && retentionMs < 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Retención inválida");
        if (cleanupPolicy != null) validCleanup(cleanupPolicy);
        if (partitions != null) {
            if (partitions > current.partitions().size())
                await(admin.createPartitions(Map.of(name, NewPartitions.increaseTo(partitions))).all());
        }
        List<AlterConfigOp> changes = new ArrayList<>();
        if (retentionMs != null) {
            changes.add(new AlterConfigOp(new ConfigEntry("retention.ms", String.valueOf(retentionMs)), AlterConfigOp.OpType.SET));
        }
        if (cleanupPolicy != null) {
            changes.add(new AlterConfigOp(new ConfigEntry("cleanup.policy", cleanupPolicy), AlterConfigOp.OpType.SET));
        }
        if (!changes.isEmpty()) await(admin.incrementalAlterConfigs(Map.of(
            new ConfigResource(ConfigResource.Type.TOPIC, name), changes)).all());
        return topic(name);
    }

    public void delete(String name) {
        if (!allowDelete) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Eliminación desactivada");
        validName(name);
        await(admin.deleteTopics(List.of(name)).all());
    }

    public List<GroupSummary> groups() {
        return await(admin.listConsumerGroups().all()).stream()
            .map(g -> new GroupSummary(g.groupId(), g.state().map(Enum::name).orElse("UNKNOWN")))
            .sorted(Comparator.comparing(GroupSummary::groupId)).toList();
    }

    public GroupDetails group(String groupId) {
        validName(groupId);
        ConsumerGroupDescription group = await(admin.describeConsumerGroups(List.of(groupId)).all()).get(groupId);
        if (group == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Grupo inexistente");
        List<MemberInfo> members = group.members().stream().map(m -> new MemberInfo(m.clientId(), m.host(),
            m.assignment().topicPartitions().stream().map(TopicPartition::toString).sorted().toList())).toList();
        Map<String, Long> offsets = new TreeMap<>();
        await(admin.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata())
            .forEach((partition, offset) -> offsets.put(partition.toString(), offset.offset()));
        return new GroupDetails(groupId, group.state().name(), members, offsets);
    }

    private String setting(Config config, String name) {
        ConfigEntry entry = config == null ? null : config.get(name);
        return entry == null || entry.isSensitive() ? null : entry.value();
    }
    private void validName(String name) {
        if (name == null || name.length() > 249 || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]*"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nombre inválido");
    }
    private void validTopology(int partitions, int replicas) {
        if (partitions < 1 || partitions > 100 || replicas < 1 || replicas > 10)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Particiones o réplicas inválidas");
    }
    private void validConfig(long retention, String cleanup) {
        if (retention < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Retención inválida");
        validCleanup(cleanup);
    }
    private void validCleanup(String cleanup) {
        if (!List.of("delete", "compact", "compact,delete").contains(cleanup))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Política de limpieza inválida");
    }
    private <T> T await(KafkaFuture<T> future) {
        try { return future.get(10, TimeUnit.SECONDS); }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Consulta Kafka interrumpida", ex);
        } catch (TimeoutException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Kafka no respondió", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof TopicExistsException) throw new ResponseStatusException(HttpStatus.CONFLICT, "Tópico existente", cause);
            if (cause instanceof UnknownTopicOrPartitionException || cause instanceof GroupIdNotFoundException)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recurso Kafka inexistente", cause);
            if (cause instanceof InvalidTopicException || cause instanceof InvalidPartitionsException
                || cause instanceof InvalidConfigurationException)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Configuración Kafka inválida", cause);
            if (cause instanceof org.apache.kafka.common.errors.AuthorizationException)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kafka rechazó la autorización", cause);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Falló la operación Kafka", cause);
        }
    }

    @Configuration
    static class AdminConfig {
        @Bean(destroyMethod = "close") Admin kafkaManagementClient(KafkaAdmin kafkaAdmin) {
            Map<String, Object> settings = new HashMap<>(kafkaAdmin.getConfigurationProperties());
            settings.putIfAbsent("default.api.timeout.ms", "10000");
            settings.putIfAbsent("request.timeout.ms", "5000");
            return Admin.create(settings);
        }
    }
}
