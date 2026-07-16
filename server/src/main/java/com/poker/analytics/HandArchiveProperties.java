package com.poker.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Local hand-archive settings. The archive is plain files and deliberately
 * independent of the database so completed hands survive a database outage.
 */
@ConfigurationProperties("poker.hand-archive")
public record HandArchiveProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("./data/hand-archive") String directory) {
}
