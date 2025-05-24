package com.atlassian.tutorial.myPlugin.ui;

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
    private GeminiPredictionService geminiService; // Поле для хранения экземпляра

    public IssueInfoContextProvider() {
        // Теперь мы создаем экземпляр сервиса напрямую через new,
        // так как GeminiPredictionService больше не является управляемым OSGi компонентом
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

            // ЗАМЕНИТЕ "customfield_10000" на ID вашего поля "Сложность"!
            CustomField complexityField = ComponentAccessor.getCustomFieldManager().getCustomFieldObject("customfield_10000");
            if (complexityField != null) {
                Object complexityValue = currentIssue.getCustomFieldValue(complexityField);
                contextMap.put("issueComplexity", complexityValue != null ? complexityValue.toString() : "N/A");
            } else {
                log.warn("Custom field 'customfield_10000' (Complexity) not found.");
                contextMap.put("issueComplexity", "N/A (Поле не найдено)");
            }

            // Получаем результат (Map) от Gemini
            if (this.geminiService != null) {
                Map<String, String> geminiResult = this.geminiService.getPredictionFromGemini(currentIssue);
                contextMap.put("geminiPrompt", geminiResult.get("prompt"));
                contextMap.put("geminiPrediction", geminiResult.get("prediction"));
                log.debug("ContextProvider: Gemini prediction: {}", geminiResult.get("prediction"));
                log.debug("ContextProvider: Gemini prompt used: {}", geminiResult.get("prompt"));
            } else {
                // Эта ветка теперь менее вероятна, так как geminiService создается в конструкторе
                contextMap.put("geminiPrediction", "Ошибка: Сервис Gemini не инициализирован.");
                contextMap.put("geminiPrompt", "N/A");
                log.error("ContextProvider: GeminiPredictionService instance is null (should not happen if constructor ran).");
            }

        } else {
            log.warn("ContextProvider: Could not retrieve current issue from context.");
            contextMap.put("error", "Не удалось загрузить данные задачи.");
        }
        return contextMap;
    }
}