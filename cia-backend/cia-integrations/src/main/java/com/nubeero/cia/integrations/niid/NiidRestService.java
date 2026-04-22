package com.nubeero.cia.integrations.niid;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@ConditionalOnProperty(name = "cia.niid.mode", havingValue = "live")
public class NiidRestService implements NiidService {

    @Value("${cia.niid.api-url}")
    private String apiUrl;

    @Override
    public NiidUploadResult uploadPolicy(NiidUploadRequest request) {
        throw new UnsupportedOperationException("Live NIID integration pending credentials");
    }
}
