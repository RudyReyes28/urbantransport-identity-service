package com.urbantransport.identity.event;

import com.urbantransport.identity.config.IdentityKafkaProperties;
import com.urbantransport.identity.domain.BranchEntity;
import com.urbantransport.identity.domain.OutboxEventEntity;
import com.urbantransport.identity.domain.Role;
import com.urbantransport.identity.domain.UserEntity;
import com.urbantransport.identity.repository.OutboxEventRepository;
import com.urbantransport.identity.service.CustomerSyncService;
import com.urbantransport.identity.service.DriverSyncService;
import com.urbantransport.identity.service.impl.OutboxServiceImpl;
import com.urbantransport.identity.web.CorrelationIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventsAndOutboxUnitTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Mock
    private CustomerSyncService customerSyncService;

    @Mock
    private DriverSyncService driverSyncService;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @AfterEach
    void tearDown() {
        MDC.clear();
        Thread.interrupted(); // Limpia bandera de interrupción si quedó activa
    }

    @Test
    void customerEventsListenerShouldProcessNitChangedAndIgnoreOthers() {
        CustomerEventsListener listener = new CustomerEventsListener(jsonMapper, customerSyncService);
        UUID eventId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        // 1. Evento válido CustomerNitChanged
        String validJson = """
                {
                  "eventId": "%s",
                  "eventType": "CustomerNitChanged",
                  "payload": { "customerId": "%s", "nit": "123456K" }
                }
                """.formatted(eventId, customerId);
        listener.onCustomerEvent(validJson);
        verify(customerSyncService).applyNitChange(eventId, customerId, "123456K");

        // 2. Otro tipo de evento -> se ignora
        String ignoredJson = """
                {
                  "eventId": "%s",
                  "eventType": "OtherCustomerEvent",
                  "payload": {}
                }
                """.formatted(UUID.randomUUID());
        listener.onCustomerEvent(ignoredJson);
        verifyNoMoreInteractions(customerSyncService);
    }

    @Test
    void fleetEventsListenerShouldProcessDriverDeactivatedAndHandleMissingUserId() {
        FleetEventsListener listener = new FleetEventsListener(jsonMapper, driverSyncService);
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // 1. Evento válido con userId
        String validJson = """
                {
                  "eventId": "%s",
                  "eventType": "DriverDeactivated",
                  "payload": { "userId": "%s" }
                }
                """.formatted(eventId, userId);
        listener.onFleetEvent(validJson);
        verify(driverSyncService).applyDriverDeactivated(eventId, userId);

        // 2. Evento DriverDeactivated pero sin userId -> no llama al servicio
        String missingUserJson = """
                {
                  "eventId": "%s",
                  "eventType": "DriverDeactivated",
                  "payload": {}
                }
                """.formatted(UUID.randomUUID());
        listener.onFleetEvent(missingUserJson);

        // 3. Evento de otro tipo -> se ignora
        String otherEventJson = """
                {
                  "eventId": "%s",
                  "eventType": "BusCreated",
                  "payload": { "userId": "%s" }
                }
                """.formatted(UUID.randomUUID(), userId);
        listener.onFleetEvent(otherEventJson);

        verifyNoMoreInteractions(driverSyncService);
    }

    @Test
    void outboxServiceShouldRecordEventWithAndWithoutMdcCorrelationId() {
        OutboxServiceImpl outboxService = new OutboxServiceImpl(outboxEventRepository, jsonMapper);
        UUID branchId = UUID.randomUUID();

        BranchEntity branch = BranchEntity.builder()
                .id(branchId)
                .name("Central")
                .code("CEN")
                .latitude(new BigDecimal("14.6349"))
                .longitude(new BigDecimal("-90.5069"))
                .active(true)
                .build();
        BranchEventPayload branchPayload = BranchEventPayload.fromEntity(branch);

        // 1. Con CorrelationId en MDC
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-123");
        outboxService.record(EventTypes.AGGREGATE_BRANCH, branchId, EventTypes.BRANCH_CREATED, branchPayload);

        // 2. Sin CorrelationId en MDC y probando UserEventPayload (con y sin sucursal)
        MDC.clear();
        UserEntity userWithBranch = UserEntity.builder()
                .id(UUID.randomUUID())
                .email("user@test.com")
                .fullName("User Test")
                .role(Role.CASHIER)
                .branch(branch)
                .active(true)
                .build();
        UserEntity userWithoutBranch = UserEntity.builder()
                .id(UUID.randomUUID())
                .email("admin@test.com")
                .fullName("Admin")
                .role(Role.SYSTEM_ADMIN)
                .branch(null)
                .active(true)
                .build();

        assertNotNull(UserEventPayload.fromEntity(userWithBranch).getBranchId());
        assertNull(UserEventPayload.fromEntity(userWithoutBranch).getBranchId());

        outboxService.record(EventTypes.AGGREGATE_USER, userWithBranch.getId(),
                EventTypes.USER_CREATED, UserEventPayload.fromEntity(userWithBranch));

        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxEventRepository, times(2)).save(captor.capture());

        List<OutboxEventEntity> saved = captor.getAllValues();
        assertTrue(saved.get(0).getPayload().contains("corr-123"));
        assertEquals(EventTypes.BRANCH_CREATED, saved.get(0).getEventType());
        assertEquals(EventTypes.USER_CREATED, saved.get(1).getEventType());
    }

    @Test
    void outboxPublisherShouldPublishAndHandleExceptions() throws Exception {
        IdentityKafkaProperties props = new IdentityKafkaProperties("identity.events", "cust.events", "fleet.events");
        OutboxPublisher publisher = new OutboxPublisher(outboxEventRepository, kafkaTemplate, props);

        OutboxEventEntity e1 = OutboxEventEntity.builder()
                .id(UUID.randomUUID())
                .aggregateId(UUID.randomUUID())
                .payload("{\"e\":1}")
                .build();
        OutboxEventEntity e2 = OutboxEventEntity.builder()
                .id(UUID.randomUUID())
                .aggregateId(UUID.randomUUID())
                .payload("{\"e\":2}")
                .build();

        // Caso 1: Envío exitoso del primero, fallo general en el segundo (corta el lote)
        when(outboxEventRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(e1, e2));
        CompletableFuture<SendResult<String, String>> okFuture = CompletableFuture.completedFuture(null);
        CompletableFuture<SendResult<String, String>> failFuture = new CompletableFuture<>();
        failFuture.completeExceptionally(new RuntimeException("Broker caído"));

        when(kafkaTemplate.send("identity.events", e1.getAggregateId().toString(), e1.getPayload())).thenReturn(okFuture);
        when(kafkaTemplate.send("identity.events", e2.getAggregateId().toString(), e2.getPayload())).thenReturn(failFuture);

        publisher.publishPending();

        verify(outboxEventRepository).markPublished(eq(e1.getId()), any(LocalDateTime.class));
        verify(outboxEventRepository, never()).markPublished(eq(e2.getId()), any(LocalDateTime.class));

        // Caso 2: InterruptedException -> restaura el flag de interrupción del hilo y sale
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, String>> interruptedFuture = mock(CompletableFuture.class);
        when(interruptedFuture.get(5, TimeUnit.SECONDS)).thenThrow(new InterruptedException("Hilo interrumpido"));
        when(outboxEventRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(e1));
        when(kafkaTemplate.send("identity.events", e1.getAggregateId().toString(), e1.getPayload())).thenReturn(interruptedFuture);

        publisher.publishPending();
        assertTrue(Thread.currentThread().isInterrupted());
    }
}