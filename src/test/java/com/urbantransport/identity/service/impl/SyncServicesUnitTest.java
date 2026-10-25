package com.urbantransport.identity.service.impl;

import com.urbantransport.identity.domain.ProcessedEventEntity;
import com.urbantransport.identity.domain.Role;
import com.urbantransport.identity.domain.UserEntity;
import com.urbantransport.identity.event.EventTypes;
import com.urbantransport.identity.repository.ProcessedEventRepository;
import com.urbantransport.identity.repository.RefreshTokenRepository;
import com.urbantransport.identity.repository.UserRepository;
import com.urbantransport.identity.service.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyncServicesUnitTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private OutboxService outboxService;

    private CustomerSyncServiceImpl customerSyncService;
    private DriverSyncServiceImpl driverSyncService;

    @BeforeEach
    void setUp() {
        customerSyncService = new CustomerSyncServiceImpl(userRepository, processedEventRepository);
        driverSyncService = new DriverSyncServiceImpl(
                userRepository, refreshTokenRepository, processedEventRepository, outboxService);
    }

    @Test
    void customerSyncShouldIgnoreAlreadyProcessedEvent() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsById(eventId)).thenReturn(true);

        customerSyncService.applyNitChange(eventId, UUID.randomUUID(), "12345");

        verifyNoInteractions(userRepository);
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void customerSyncShouldUpdateNitWhenUserExistsAndRecordEventWhenUserDoesNotExist() {
        UUID eventId1 = UUID.randomUUID();
        UUID customerId1 = UUID.randomUUID();
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).customerId(customerId1).nit("OLD").build();

        when(processedEventRepository.existsById(eventId1)).thenReturn(false);
        when(userRepository.findByCustomerId(customerId1)).thenReturn(Optional.of(user));

        customerSyncService.applyNitChange(eventId1, customerId1, " 987654k ");
        assertEquals("987654K", user.getNit());
        verify(processedEventRepository).save(any(ProcessedEventEntity.class));

        // Cuando el cliente no existe en identity
        UUID eventId2 = UUID.randomUUID();
        UUID customerId2 = UUID.randomUUID();
        when(processedEventRepository.existsById(eventId2)).thenReturn(false);
        when(userRepository.findByCustomerId(customerId2)).thenReturn(Optional.empty());

        customerSyncService.applyNitChange(eventId2, customerId2, "11111");
        verify(processedEventRepository, times(2)).save(any(ProcessedEventEntity.class));
    }

    @Test
    void driverSyncShouldHandleAllBranches() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // 1. Evento ya procesado
        when(processedEventRepository.existsById(eventId)).thenReturn(true);
        driverSyncService.applyDriverDeactivated(eventId, userId);
        verifyNoInteractions(userRepository);

        // 2. Usuario es DRIVER y está activo -> desactiva, revoca tokens y graba outbox
        UUID activeDriverEvent = UUID.randomUUID();
        UserEntity activeDriver = UserEntity.builder().id(userId).role(Role.DRIVER).active(true).build();
        when(processedEventRepository.existsById(activeDriverEvent)).thenReturn(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(activeDriver));

        driverSyncService.applyDriverDeactivated(activeDriverEvent, userId);
        assertFalse(activeDriver.isActive());
        verify(refreshTokenRepository).revokeAllByUserId(userId);
        verify(outboxService).record(eq(EventTypes.AGGREGATE_USER), eq(userId), eq(EventTypes.USER_DEACTIVATED), any());

        // 3. Usuario no es DRIVER (ej. CASHIER) -> no lo desactiva
        UUID nonDriverEvent = UUID.randomUUID();
        UserEntity cashier = UserEntity.builder().id(userId).role(Role.CASHIER).active(true).build();
        when(processedEventRepository.existsById(nonDriverEvent)).thenReturn(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(cashier));

        driverSyncService.applyDriverDeactivated(nonDriverEvent, userId);
        assertTrue(cashier.isActive());

        // 4. Usuario es DRIVER pero ya estaba inactivo -> no vuelve a emitir outbox
        UUID inactiveDriverEvent = UUID.randomUUID();
        UserEntity inactiveDriver = UserEntity.builder().id(userId).role(Role.DRIVER).active(false).build();
        when(processedEventRepository.existsById(inactiveDriverEvent)).thenReturn(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(inactiveDriver));

        driverSyncService.applyDriverDeactivated(inactiveDriverEvent, userId);
        verifyNoMoreInteractions(outboxService);

        // 5. Usuario no existe en BD
        UUID missingUserEvent = UUID.randomUUID();
        when(processedEventRepository.existsById(missingUserEvent)).thenReturn(false);
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        driverSyncService.applyDriverDeactivated(missingUserEvent, userId);
        verify(processedEventRepository, times(4)).save(any(ProcessedEventEntity.class));
    }
}