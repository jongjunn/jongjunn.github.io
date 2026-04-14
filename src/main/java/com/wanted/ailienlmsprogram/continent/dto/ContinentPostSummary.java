package com.wanted.ailienlmsprogram.continent.dto;

import java.time.LocalDateTime;

public interface ContinentPostSummary {
    Long getPostId();
    String getTitle();
    String getWriterName();
    Boolean getNotice();
    LocalDateTime getCreatedAt();
}