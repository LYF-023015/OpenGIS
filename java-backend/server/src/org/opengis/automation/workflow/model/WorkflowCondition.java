/** 文件职责：workflow 后端领域：实现该文件名所对应的单一职责。 */
package org.opengis.automation.workflow.model;

import tools.jackson.databind.JsonNode;

/** Safe JSON-logic condition; onFalse is fail, skip, or retry. */
public record WorkflowCondition(JsonNode expression, String description, String onFalse) {}
