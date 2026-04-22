package com.nubeero.cia.storage;

import java.io.InputStream;

public interface DocumentStorageService {

    /** Uploads content and returns the storage path/key. */
    String upload(String tenantId, String path, InputStream content, String mimeType);

    InputStream download(String tenantId, String path);

    void delete(String tenantId, String path);

    /** Returns a pre-signed URL valid for {@code expirySeconds} seconds. */
    String presignedUrl(String tenantId, String path, long expirySeconds);
}
