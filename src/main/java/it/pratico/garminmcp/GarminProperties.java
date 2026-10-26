package it.pratico.garminmcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param baseUrl    Personal API host
 * @param apiKeyFile file containing the personal API key (used when env GARMIN_PERSONAL_API_KEY is not set)
 * @param dataDir    folder where downloaded .FIT files are stored
 */
@ConfigurationProperties(prefix = "garmin")
public record GarminProperties(String baseUrl, String apiKeyFile, String dataDir) {
}
