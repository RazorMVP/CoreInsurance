package com.nubeero.cia.partner.app;

import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.partner.app.dto.CreatePartnerAppRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PartnerAppService {

    private final PartnerAppRepository repository;

    public Page<PartnerApp> list(Pageable pageable) {
        return repository.findAll(pageable);
    }

    public PartnerApp findOrThrow(UUID id) {
        return repository.findById(id)
                .filter(a -> a.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("PartnerApp", id));
    }

    @Transactional
    public PartnerApp create(CreatePartnerAppRequest req) {
        if (repository.findByClientId(req.clientId()).isPresent()) {
            throw new BusinessRuleException("DUPLICATE_CLIENT_ID",
                    "A partner app with clientId '" + req.clientId() + "' already exists");
        }
        return repository.save(PartnerApp.builder()
                .clientId(req.clientId())
                .appName(req.appName())
                .contactEmail(req.contactEmail())
                .scopes(req.scopes() != null ? req.scopes() : "")
                .plan(req.plan())
                .rateLimitRpm(req.rateLimitRpm() > 0 ? req.rateLimitRpm() : 60)
                .allowedIps(req.allowedIps())
                .active(true)
                .build());
    }

    @Transactional
    public PartnerApp toggleActive(UUID id, boolean active) {
        PartnerApp app = findOrThrow(id);
        app.setActive(active);
        return repository.save(app);
    }

    @Transactional
    public void delete(UUID id) {
        PartnerApp app = findOrThrow(id);
        app.softDelete();
        app.setActive(false);
        repository.save(app);
    }
}
