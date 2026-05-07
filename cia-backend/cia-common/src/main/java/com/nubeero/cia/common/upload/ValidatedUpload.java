package com.nubeero.cia.common.upload;

public record ValidatedUpload(String originalFilename, String safeFilename, String contentType, byte[] content) {

    public long size() {
        return content.length;
    }
}
