package com.nubeero.cia.integrations.niid;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@ConditionalOnProperty(name = "cia.niid.mode", havingValue = "stub", matchIfMissing = true)
public class StubNiidService implements NiidService {

    @Override
    public NiidUploadResult uploadPolicy(NiidUploadRequest request) {
        String ref = "NIID-STUB-" + UUID.randomUUID();
        log.info("[NIID STUB] Uploaded policy={} ref={}", request.getPolicyNumber(), ref);
        return NiidUploadResult.builder().success(true).niidRef(ref).build();
    }
}
