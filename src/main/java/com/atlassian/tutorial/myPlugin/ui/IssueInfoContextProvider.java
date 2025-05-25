package com.atlassian.tutorial.myPlugin.ui;

// ... (все существующие импорты) ...
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.plugin.webfragment.contextproviders.AbstractJiraContextProvider;
import com.atlassian.jira.plugin.webfragment.model.JiraHelper;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.tutorial.myPlugin.service.GeminiPredictionService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class IssueInfoContextProvider extends AbstractJiraContextProvider {

    private static final Logger log = LoggerFactory.getLogger(IssueInfoContextProvider.class);
    private GeminiPredictionService geminiService;

    public IssueInfoContextProvider() {
        this.geminiService = new GeminiPredictionService();
    }

    @Override
    public Map<String, Object> getContextMap(ApplicationUser user, JiraHelper jiraHelper) {
        Map<String, Object> contextMap = new HashMap<>();
        Issue currentIssue = (Issue) jiraHelper.getContextParams().get("issue");

        if (currentIssue != null) {
            log.debug("ContextProvider: Retrieved issue: {}", currentIssue.getKey());

            contextMap.put("issueKey", currentIssue.getKey());
            contextMap.put("issueTypeName", currentIssue.getIssueType().getName());
            contextMap.put("issuePriorityName", currentIssue.getPriority() != null ? currentIssue.getPriority().getName() : "N/A");

            // Получаем значение сложности для отображения
            com.atlassian.jira.issue.fields.CustomField complexityFieldForDisplay = ComponentAccessor.getCustomFieldManager().getCustomFieldObject(GeminiPredictionService.COMPLEXITY_FIELD_ID); // Используем константу
            if (complexityFieldForDisplay != null) {
                Object complexityValue = currentIssue.getCustomFieldValue(complexityFieldForDisplay);
                contextMap.put("issueComplexity", complexityValue != null ? complexityValue.toString() : "N/A");
            } else {
                log.warn("Custom field '{}' (Complexity) not found for display.", GeminiPredictionService.COMPLEXITY_FIELD_ID);
                contextMap.put("issueComplexity", "N/A (Поле не найдено)");
            }

            if (this.geminiService != null) {
                // Получаем прогноз от Gemini
                Map<String, String> geminiResult = this.geminiService.getPredictionFromGemini(currentIssue);
                contextMap.put("geminiPrompt", geminiResult.get("prompt"));
                contextMap.put("geminiPrediction", geminiResult.get("prediction"));
                log.debug("ContextProvider: Gemini prediction: {}", geminiResult.get("prediction"));

                // Получаем прогноз на основе среднего
                String averageTimePrediction = this.geminiService.getAverageTimeByComplexity(currentIssue);
                contextMap.put("averageTimePrediction", averageTimePrediction);
                log.debug("ContextProvider: Average time prediction: {}", averageTimePrediction);

            } else {
                contextMap.put("geminiPrediction", "Ошибка: Сервис Gemini не инициализирован.");
                contextMap.put("geminiPrompt", "N/A");
                contextMap.put("averageTimePrediction", "Ошибка: Сервис не инициализирован.");
                log.error("ContextProvider: GeminiPredictionService instance is null!");
            }

        } else {
            log.warn("ContextProvider: Could not retrieve current issue from context.");
            contextMap.put("error", "Не удалось загрузить данные задачи.");
        }
        return contextMap;
    }
}