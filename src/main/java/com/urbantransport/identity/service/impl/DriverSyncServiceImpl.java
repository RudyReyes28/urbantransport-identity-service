package com.urbantransport.identity.service.impl;

import com.urbantransport.identity.domain.ProcessedEventEntity;
import com.urbantransport.identity.domain.Role;
import com.urbantransport.identity.event.EventTypes;
import com.urbantransport.identity.event.UserEventPayload;
import com.urbantransport.identity.repository.ProcessedEventRepository;
import com.urbantransport.identity.repository.RefreshTokenRepository;
import com.urbantransport.identity.repository.UserRepository;
import com.urbantransport.identity.service.DriverSyncService;
import com.urbantransport.identity.service.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DriverSyncServiceImpl implements DriverSyncService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final OutboxService outboxService;

    @Override
    @Transactional
    public void applyDriverDeactivated(UUID eventId, UUID userId) {
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Evento {} ya procesado, se ignora", eventId);
            return;
        }
        userRepository.findById(userId).ifPresentOrElse(
                user -> {
                    if (user.getRole() != Role.DRIVER) {
                        log.warn("El usuario {} no es chofer, no se desactiva", userId);
                    } else if (user.isActive()) {
                        user.setActive(false);
                        refreshTokenRepository.revokeAllByUserId(user.getId());
                        outboxService.record(EventTypes.AGGREGATE_USER, user.getId(), EventTypes.USER_DEACTIVATED,
                                UserEventPayload.fromEntity(user));
                        log.info("Cuenta del chofer {} desactivada", userId);
                    }
                },
                () -> log.info("El usuario {} no existe en identity, no hay nada que desactivar", userId));
        processedEventRepository.save(ProcessedEventEntity.builder().eventId(eventId).build());
    }
}
