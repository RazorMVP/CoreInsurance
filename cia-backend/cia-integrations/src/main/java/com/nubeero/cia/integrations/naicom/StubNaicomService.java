package com.nubeero.cia.integrations.naicom;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@ConditionalOnProperty(name = "cia.naicom.mode", havingValue = "stub", matchIfMissing = true)
public class StubNaicomService implements NaicomService {

    @Override
    public NaicomUploadResult uploadPolicy(NaicomUploadRequest request) {
        String uid = "NAICOM-STUB-" + UUID.randomUUID();
        log.info("[NAICOM STUB] Uploaded policy={} uid={}", request.getPolicyNumber(), uid);
        return NaicomUploadResult.builder().success(true).naicomUid(uid).build();
    }
}
