package org.opengis.workflow.model;

import tools.jackson.databind.JsonNode;

/** Safe JSON-logic condition; onFalse is fail, skip, or retry. */
public record WorkflowCondition(JsonNode expression, String description, String onFalse) {}
