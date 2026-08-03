package org.opengis.server.health;

/** Compatibility response for the existing Renderer health probe. */
public record HealthResponse(String status, String version) {}
