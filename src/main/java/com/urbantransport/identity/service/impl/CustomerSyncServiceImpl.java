package com.urbantransport.identity.service.impl;

import com.urbantransport.identity.domain.ProcessedEventEntity;
import com.urbantransport.identity.repository.ProcessedEventRepository;
import com.urbantransport.identity.repository.UserRepository;
import com.urbantransport.identity.service.CustomerSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerSyncServiceImpl implements CustomerSyncService {

    private final UserRepository userRepository;
    private final ProcessedEventRepository processedEventRepository;

    /** El efecto y el registro del eventId van en la misma transacción: o ambos o ninguno. */
    @Override
    @Transactional
    public void applyNitChange(UUID eventId, UUID customerId, String nit) {
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Evento {} ya procesado, se ignora", eventId);
            return;
        }
        userRepository.findByCustomerId(customerId).ifPresentOrElse(
                user -> {
                    user.setNit(nit.trim().toUpperCase(Locale.ROOT));
                    log.info("NIT actualizado para el cliente {}", customerId);
                },
                () -> log.info("El cliente {} no tiene cuenta en identity, no hay nada que actualizar", customerId));
        processedEventRepository.save(ProcessedEventEntity.builder().eventId(eventId).build());
    }
}