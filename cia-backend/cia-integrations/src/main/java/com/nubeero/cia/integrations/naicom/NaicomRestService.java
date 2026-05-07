package com.nubeero.cia.integrations.naicom;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@ConditionalOnProperty(name = "cia.naicom.mode", havingValue = "live")
public class NaicomRestService implements NaicomService {

    @Value("${cia.naicom.api-url}")
    private String apiUrl;

    @PostConstruct
    public void failUntilLiveIntegrationIsImplemented() {
        throw new IllegalStateException(
                "NAICOM live integration is not implemented yet. " +
                "Do not enable NAICOM_MODE=live until go-live provider contract work is complete."
        );
    }

    @Override
    public NaicomUploadResult uploadPolicy(NaicomUploadRequest request) {
        throw new UnsupportedOperationException("Live NAICOM integration pending credentials");
    }
}
