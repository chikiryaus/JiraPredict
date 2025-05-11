package com.atlassian.tutorial.myPlugin.ui;

import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.plugin.webfragment.contextproviders.AbstractJiraContextProvider;
import com.atlassian.jira.plugin.webfragment.model.JiraHelper;
import com.atlassian.jira.user.ApplicationUser;


import java.util.HashMap;
import java.util.Map;

// Важно: Этот класс НЕ обязательно должен быть @Named,
// так как он указывается напрямую в atlassian-plugin.xml
// и создается фреймворком веб-фрагментов.
// Но ему могут потребоваться зависимости, которые нужно внедрять,
// тогда его лучше сделать @Named и внедрять зависимости через конструктор.
// Пока он простой, оставим так.
public class IssueInfoContextProvider extends AbstractJiraContextProvider {



    // Конструктор по умолчанию
    public IssueInfoContextProvider() {
        // Если нужны зависимости (например, ваш PredictionService),
        // объявите их как поля и добавьте конструктор с @Inject
    }

    @Override
    public Map<String, Object> getContextMap(ApplicationUser user, JiraHelper jiraHelper) {
        Map<String, Object> contextMap = new HashMap<>();

        // Получаем текущий объект Issue из контекста JiraHelper
        Issue currentIssue = (Issue) jiraHelper.getContextParams().get("issue");

        // Проверяем, что задача получена
        if (currentIssue != null) {

            // Кладем нужные данные из объекта Issue в contextMap
            // Ключи мапы будут именами переменных в Velocity-шаблоне

            contextMap.put("issueKey", currentIssue.getKey()); // Ключ задачи (e.g., MYPROJ-123)
            contextMap.put("issueSummary", currentIssue.getSummary()); // Заголовок задачи
            contextMap.put("issueTypeName", currentIssue.getIssueType().getName()); // Название типа задачи (e.g., "Bug")
            contextMap.put("issuePriorityName", currentIssue.getPriority() != null ? currentIssue.getPriority().getName() : "None"); // Название приоритета (проверяем на null)
            contextMap.put("issueProjectName", currentIssue.getProjectObject().getName()); // Название проекта
            contextMap.put("issueProjectId", currentIssue.getProjectObject().getId()); // ID проекта
            contextMap.put("issueStatusName", currentIssue.getStatus().getName()); // Название текущего статуса
            contextMap.put("issueReporterName", currentIssue.getReporter() != null ? currentIssue.getReporter().getDisplayName() : "None"); // Отображаемое имя автора (проверяем на null)
            contextMap.put("issueAssigneeName", currentIssue.getAssignee() != null ? currentIssue.getAssignee().getDisplayName() : "Unassigned"); // Отображаемое имя исполнителя (проверяем на null)
            contextMap.put("issueCreatedDate", currentIssue.getCreated()); // Дата создания (java.sql.Timestamp)
            contextMap.put("issueUpdatedDate", currentIssue.getUpdated()); // Дата последнего обновления (java.sql.Timestamp)
            contextMap.put("issueResolutionDate", currentIssue.getResolutionDate()); // Дата решения (java.sql.Timestamp, будет null, если не решена)

            // CustomField myCustomField = ComponentAccessor.getCustomFieldManager().getCustomFieldObject("customfield_10010"); // Замените ID!
            // Object customFieldValue = currentIssue.getCustomFieldValue(myCustomField);
            // contextMap.put("myCustomFieldValue", customFieldValue != null ? customFieldValue.toString() : "N/A");

        } else {
            // Можно положить флаг ошибки в мапу, чтобы шаблон знал об этом
            contextMap.put("error", "Could not load issue data.");
        }


        return contextMap;
    }
}