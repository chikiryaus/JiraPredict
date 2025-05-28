package com.atlassian.tutorial.myPlugin.service;

import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import com.atlassian.jira.component.ComponentAccessor; // Нужен для PluginSettingsFactory, если не внедряем
// ... остальные импорты ...
import com.atlassian.jira.bc.issue.search.SearchService;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.customfields.option.Option;
import com.atlassian.jira.issue.search.SearchException;
import com.atlassian.jira.issue.search.SearchResults;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.web.bean.PagerFilter;
import com.atlassian.query.order.SortOrder;


import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


public class GeminiPredictionService {

    private static final Logger log = LoggerFactory.getLogger(GeminiPredictionService.class);
    // Ключ, под которым будет храниться настройка в Jira
    public static final String GEMINI_API_KEY_PLUGIN_SETTING = "com.atlassian.tutorial.myPlugin.geminiApiKey";
    private static final String GEMINI_API_URL_BASE = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=";
    private static final int TARGET_EXAMPLE_ISSUES_COUNT_FOR_LLM = 5;
    public static final String COMPLEXITY_FIELD_ID = "customfield_10000"; // ЗАМЕНИТЕ!

    private String apiKey; // Теперь не final, т.к. может обновляться

    public GeminiPredictionService() {
        // Загружаем ключ при инициализации сервиса
        loadApiKeyFromSettings();
        if (this.apiKey == null || this.apiKey.trim().isEmpty()) {
            log.warn("Gemini API Key is not configured in plugin settings! Please configure it via the admin page.");
        } else {
            log.info("GeminiPredictionService initialized. API Key loaded from settings.");
        }
    }

    // Метод для загрузки ключа из настроек плагина
    private void loadApiKeyFromSettings() {
        PluginSettingsFactory pluginSettingsFactory = ComponentAccessor.getOSGiComponentInstanceOfType(PluginSettingsFactory.class);
        if (pluginSettingsFactory != null) {
            PluginSettings settings = pluginSettingsFactory.createGlobalSettings(); // Используем глобальные настройки
            this.apiKey = (String) settings.get(GEMINI_API_KEY_PLUGIN_SETTING);
            if (this.apiKey != null) this.apiKey = this.apiKey.trim(); // Убираем пробелы
            log.debug("Loaded API Key from settings: '{}'", (this.apiKey != null && !this.apiKey.isEmpty()) ? "****" + this.apiKey.substring(Math.max(0, this.apiKey.length() - 4)) : "null or empty");
        } else {
            log.error("PluginSettingsFactory is null! Cannot load API key from settings.");
            this.apiKey = null;
        }
    }

    // Публичный метод для обновления ключа, если он изменился в настройках
    // Может вызываться перед каждым запросом к Gemini или реже, если это необходимо
    public void refreshApiKey() {
        log.debug("Refreshing API Key...");
        loadApiKeyFromSettings();
    }

    public Map<String, String> getPredictionFromGemini(Issue currentIssue) {
        refreshApiKey();
        Map<String, String> result = new HashMap<>();
        result.put("prompt", "");
        result.put("prediction", "");

        // Убедимся, что ключ актуален (можно вызывать не всегда, а по необходимости)
        // refreshApiKey(); // Если ключ мог измениться с момента создания сервиса

        if (this.apiKey == null || this.apiKey.trim().isEmpty()) {
            result.put("prediction", "Ошибка: API ключ Gemini не настроен в плагине. Пожалуйста, укажите его на странице конфигурации плагина.");
            return result;
        }
        // ... (остальная часть метода getPredictionFromGemini как в вашем рабочем коде) ...
        if (currentIssue == null) {
            log.warn("Issue object is null in getPredictionFromGemini.");
            result.put("prediction", "Ошибка: Объект текущей задачи не предоставлен.");
            return result;
        }

        SearchService searchService = ComponentAccessor.getOSGiComponentInstanceOfType(SearchService.class);
        if (searchService == null) {
            log.error("SearchService is null! Cannot search for similar issues.");
            result.put("prediction", "Ошибка: Сервис поиска Jira недоступен.");
            String promptWithoutExamples = buildPromptForIssueWithExamples(currentIssue, new ArrayList<>());
            result.put("prompt", promptWithoutExamples);
            return sendToGemini(promptWithoutExamples, result);
        }

        List<Issue> exampleIssues = findResolvedIssuesInSameProject(currentIssue, searchService, TARGET_EXAMPLE_ISSUES_COUNT_FOR_LLM);
        String prompt = buildPromptForIssueWithExamples(currentIssue, exampleIssues);
        log.info("Final prompt for Gemini (length: {} chars, {} examples): {}", prompt.length(), exampleIssues.size(), prompt.substring(0, Math.min(prompt.length(), 200)) + "...");
        result.put("prompt", prompt);

        return sendToGemini(prompt, result);

    }
    // ... (остальные методы: sendToGemini, findResolvedIssuesInSameProject, buildPromptForIssueWithExamples, parseGeminiResponse - остаются как были)
    // Метод sendToGemini остается таким же
    private Map<String, String> sendToGemini(String prompt, Map<String, String> resultAccumulator) {
        // ... (код идентичен предыдущему рабочему варианту) ...
        JSONObject contentPart = new JSONObject();
        contentPart.put("text", prompt);
        JSONArray partsArray = new JSONArray();
        partsArray.put(contentPart);
        JSONObject contentsObject = new JSONObject();
        contentsObject.put("parts", partsArray);
        JSONArray contentsArray = new JSONArray();
        contentsArray.put(contentsObject);
        JSONObject requestBodyJson = new JSONObject();
        requestBodyJson.put("contents", contentsArray);
        String requestBody = requestBodyJson.toString();

        try {
            URL url = new URL(GEMINI_API_URL_BASE + this.apiKey);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            log.info("Gemini API Response Code: {}", responseCode);

            StringBuilder responseContent = new StringBuilder();
            BufferedReader reader = null;

            if (responseCode >= 200 && responseCode < 300) {
                reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            } else {
                if (conn.getErrorStream() != null) {
                    reader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
                }
            }
            if (reader != null) {
                String responseLine;
                while ((responseLine = reader.readLine()) != null) {
                    responseContent.append(responseLine.trim());
                }
                reader.close();
            }
            String rawResponse = responseContent.toString();
            log.debug("Gemini API Raw Response: {}", rawResponse);

            if (responseCode == HttpURLConnection.HTTP_OK) {
                resultAccumulator.put("prediction", parseGeminiResponse(rawResponse));
            } else {
                log.error("Gemini API Error Response ({}): {}", responseCode, rawResponse);
                resultAccumulator.put("prediction", "Ошибка Gemini API: " + responseCode + " - " + rawResponse.substring(0, Math.min(rawResponse.length(), 150)) + "...");
            }
        } catch (java.net.SocketTimeoutException e) {
            log.error("Timeout while calling Gemini API: ", e);
            resultAccumulator.put("prediction", "Ошибка: Таймаут при обращении к Gemini.");
        } catch (Exception e) {
            log.error("Exception while calling Gemini API: ", e);
            resultAccumulator.put("prediction", "Ошибка при обращении к Gemini: " + e.getMessage());
        }
        return resultAccumulator;
    }

    private List<Issue> findResolvedIssuesInSameProject(Issue currentIssue, SearchService searchService, int limit) {
        List<Issue> exampleIssues = new ArrayList<>();
        ApplicationUser searchUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser();
        if (searchUser == null) { searchUser = ComponentAccessor.getUserManager().getUserByName("admin");}
        if (searchUser == null) { log.error("Cannot find user for example search."); return exampleIssues; }

        String projectKey = currentIssue.getProjectObject().getKey();
        String currentIssueKey = currentIssue.getKey();
        String jqlQuery = String.format("project = \"%s\" AND resolutiondate IS NOT EMPTY AND key != \"%s\" ORDER BY resolutiondate DESC",
                projectKey.replace("\"", "\\\""),
                currentIssueKey.replace("\"", "\\\""));
        log.info("JQL for LLM examples (same project, resolved): {}", jqlQuery);
        SearchService.ParseResult parseResult = searchService.parseQuery(searchUser, jqlQuery);
        if (!parseResult.isValid()) {
            log.error("Invalid JQL for LLM examples: {}. Errors: {}", jqlQuery, parseResult.getErrors().getErrorMessages());
            return exampleIssues;
        }
        try {
            SearchResults searchResults = searchService.search(searchUser, parseResult.getQuery(), new PagerFilter(limit));
            if (searchResults != null) { exampleIssues.addAll(searchResults.getIssues()); }
        } catch (SearchException e) { log.error("SearchException for LLM examples: {}", e.getMessage()); }
        return exampleIssues;
    }

    private String buildPromptForIssueWithExamples(Issue currentIssue, List<Issue> exampleIssues) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("Ты - эксперт по оценке времени выполнения задач в Jira. Твоя задача - на основе предоставленной информации о задаче дать краткий прогноз времени, необходимого для ее полного выполнения. Пожалуйста, ответь только предполагаемым временем (например, '2-3 дня', 'около 4 часов', '1 неделя', '3-5 рабочих дней'). Не добавляй никаких других объяснений, извинений или вводных фраз.\n\n");
        promptBuilder.append("--- Информация об оцениваемой задаче ---\n");
        promptBuilder.append("Заголовок: ").append(currentIssue.getSummary()).append("\n");
        if (currentIssue.getDescription() != null && !currentIssue.getDescription().trim().isEmpty()) {
            String descriptionText = currentIssue.getDescription().replaceAll("<[^>]*>", "").trim();
            promptBuilder.append("Описание: ").append(descriptionText).append("\n");
        }
        promptBuilder.append("Тип задачи: ").append(currentIssue.getIssueType().getName()).append("\n");
        if (currentIssue.getPriority() != null) {
            promptBuilder.append("Приоритет: ").append(currentIssue.getPriority().getName()).append("\n");
        }
        promptBuilder.append("Проект: ").append(currentIssue.getProjectObject().getName()).append("\n");

        com.atlassian.jira.issue.fields.CustomField complexityField = ComponentAccessor.getCustomFieldManager().getCustomFieldObject(COMPLEXITY_FIELD_ID);
        Object currentComplexityValue = null;
        if (complexityField != null) {
            currentComplexityValue = currentIssue.getCustomFieldValue(complexityField);
            promptBuilder.append("Уровень сложности (от 1 до 10): ").append(currentComplexityValue != null ? currentComplexityValue.toString() : "Не указан").append("\n");
        } else {
            promptBuilder.append("Уровень сложности (от 1 до 10): Поле не найдено\n");
        }

        if (exampleIssues != null && !exampleIssues.isEmpty()) {
            promptBuilder.append("\n--- Примеры похожих решенных задач из того же проекта (для контекста) ---\n");
            int count = 0;
            for (Issue example : exampleIssues) {
                count++;
                promptBuilder.append("\nПример ").append(count).append(":\n");
                promptBuilder.append("  Заголовок: ").append(example.getSummary()).append("\n");
                if (example.getDescription() != null && !example.getDescription().trim().isEmpty()) {
                    String exDescriptionText = example.getDescription().replaceAll("<[^>]*>", "").trim();
                    promptBuilder.append("  Описание примера: ").append(exDescriptionText.substring(0, Math.min(exDescriptionText.length(), 300))).append(exDescriptionText.length() > 300 ? "...\n" : "\n");
                }
                promptBuilder.append("  Тип: ").append(example.getIssueType().getName()).append("\n");

                Object exampleComplexity = null;
                if (complexityField != null) {
                    exampleComplexity = example.getCustomFieldValue(complexityField);
                }
                promptBuilder.append("  Сложность: ").append(exampleComplexity != null ? exampleComplexity.toString() : "N/A").append("\n");

                if (example.getResolutionDate() != null && example.getCreated() != null) {
                    long durationMillis = example.getResolutionDate().getTime() - example.getCreated().getTime();
                    long durationDays = TimeUnit.MILLISECONDS.toDays(durationMillis);
                    long remainingMillis = durationMillis - TimeUnit.DAYS.toMillis(durationDays);
                    long durationHours = TimeUnit.MILLISECONDS.toHours(remainingMillis);

                    StringBuilder timeStr = new StringBuilder();
                    if (durationDays > 0) timeStr.append(durationDays).append(" дн. ");
                    if (durationHours > 0 || durationDays == 0) timeStr.append(durationHours).append(" ч.");
                    if (timeStr.length() == 0) timeStr.append("менее 1 ч.");

                    promptBuilder.append("  Фактическое время выполнения: ").append(timeStr.toString().trim()).append("\n");
                } else {
                    promptBuilder.append("  Фактическое время выполнения: Неизвестно\n");
                }
            }
        }
        promptBuilder.append("\n\n--- Запрос ---\n");
        promptBuilder.append("Основываясь на информации об оцениваемой задаче и, если есть, примерах выше, дай краткий прогноз времени, необходимого для ее полного выполнения. Ответь только предполагаемым временем (например, '2-3 дня', 'около 4 часов', '1 неделя', '3-5 рабочих дней'). Не добавляй никаких других объяснений, извинений или вводных фраз.");
        promptBuilder.append("\nТвой прогноз времени выполнения (только оценка времени):");
        return promptBuilder.toString();
    }

    private String parseGeminiResponse(String jsonResponse) {
        // ... (код тот же) ...
        try {
            JSONObject responseJson = new JSONObject(jsonResponse);
            if (responseJson.has("candidates")) {
                JSONArray candidates = responseJson.getJSONArray("candidates");
                if (candidates.length() > 0) {
                    JSONObject firstCandidate = candidates.getJSONObject(0);
                    if (firstCandidate.has("content")) {
                        JSONObject content = firstCandidate.getJSONObject("content");
                        if (content.has("parts")) {
                            JSONArray parts = content.getJSONArray("parts");
                            if (parts.length() > 0) {
                                JSONObject firstPart = parts.getJSONObject(0);
                                if (firstPart.has("text")) {
                                    return firstPart.getString("text").trim();
                                }
                            }
                        }
                    }
                }
            }
            if (responseJson.has("promptFeedback") && responseJson.getJSONObject("promptFeedback").has("blockReason")) {
                String reason = responseJson.getJSONObject("promptFeedback").getString("blockReason");
                log.warn("Gemini response blocked, reason: {}", reason);
                return "Ответ Gemini заблокирован: " + reason;
            }
            log.warn("Could not parse Gemini response structure or response is empty: {}", jsonResponse);
            return "Не удалось извлечь текст из ответа Gemini.";
        } catch (Exception e) {
            log.error("Error parsing Gemini JSON response: '{}'", jsonResponse, e);
            return "Ошибка парсинга ответа Gemini.";
        }
    }

    // Метод getAverageTimeByComplexity остается таким же
    public String getAverageTimeByComplexity(Issue currentIssue) {
        // ... (код тот же) ...
        if (currentIssue == null) {
            log.warn("Current issue is null for average time calculation.");
            return "N/A (задача не найдена)";
        }

        SearchService searchService = ComponentAccessor.getOSGiComponentInstanceOfType(SearchService.class);
        if (searchService == null) {
            log.error("SearchService is null! Cannot calculate average time.");
            return "N/A (сервис поиска недоступен)";
        }

        ApplicationUser searchUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser();
        if (searchUser == null) {
            searchUser = ComponentAccessor.getUserManager().getUserByName("admin"); // Запасной вариант
        }
        if (searchUser == null) {
            log.error("Cannot find a user to perform search for average time calculation.");
            return "N/A (пользователь для поиска не найден)";
        }

        CustomField complexityField = ComponentAccessor.getCustomFieldManager().getCustomFieldObject(COMPLEXITY_FIELD_ID);
        if (complexityField == null) {
            log.warn("Complexity field with ID '{}' not found. Cannot calculate average by complexity.", COMPLEXITY_FIELD_ID);
            return "N/A (поле сложности не найдено)";
        }

        Object rawComplexityValue = currentIssue.getCustomFieldValue(complexityField);
        if (rawComplexityValue == null) {
            log.info("Current issue has no complexity value. Cannot calculate average by complexity.");
            return "N/A (сложность не указана)";
        }

        String complexitySearchTerm;
        if (rawComplexityValue instanceof Option) {
            complexitySearchTerm = ((Option) rawComplexityValue).getValue();
        } else {
            complexitySearchTerm = rawComplexityValue.toString();
        }
        log.info("Calculating average time for complexity: \"{}\"", complexitySearchTerm);

        String jqlQuery = String.format("cf[%s] = \"%s\" AND resolutiondate IS NOT EMPTY",
                COMPLEXITY_FIELD_ID.replace("customfield_", ""),
                complexitySearchTerm.replace("\"", "\\\""));


        log.info("JQL for average time calculation: {}", jqlQuery);

        SearchService.ParseResult parseResult = searchService.parseQuery(searchUser, jqlQuery);
        if (!parseResult.isValid()) {
            log.error("Invalid JQL query for average time: {}. Errors: {}", jqlQuery, parseResult.getErrors().getErrorMessages());
            return "N/A (ошибка JQL)";
        }

        long totalDurationMillis = 0;
        int resolvedIssuesCount = 0;
        List<Issue> foundIssues;

        try {
            SearchResults searchResults = searchService.search(searchUser, parseResult.getQuery(), PagerFilter.getUnlimitedFilter());

            if (searchResults != null) {
                foundIssues = searchResults.getIssues();
                log.info("Found {} issues with same complexity for average calculation.", foundIssues.size());

                for (Issue resolvedIssue : foundIssues) {
                    if (resolvedIssue.getCreated() != null && resolvedIssue.getResolutionDate() != null) {
                        long duration = resolvedIssue.getResolutionDate().getTime() - resolvedIssue.getCreated().getTime();
                        totalDurationMillis += duration;
                        resolvedIssuesCount++;
                    }
                }
            }
        } catch (SearchException e) {
            log.error("SearchException during average time calculation: {}", e.getMessage());
            return "N/A (ошибка поиска)";
        }

        if (resolvedIssuesCount > 0) {
            long avgMillis = totalDurationMillis / resolvedIssuesCount;
            long avgDays = TimeUnit.MILLISECONDS.toDays(avgMillis);
            long remainingMillis = avgMillis - TimeUnit.DAYS.toMillis(avgDays);
            long avgHours = TimeUnit.MILLISECONDS.toHours(remainingMillis);

            String avgTimeStr = "";
            if (avgDays > 0) {
                avgTimeStr += avgDays + " дн. ";
            }
            if (avgHours > 0 || avgDays == 0) {
                avgTimeStr += avgHours + " ч.";
            }
            if (avgTimeStr.isEmpty()) {
                avgTimeStr = "менее 1 ч.";
            }
            return String.format("~%s (на основе %d задач)", avgTimeStr.trim(), resolvedIssuesCount);
        } else {
            return "Нет данных (0 задач с такой сложностью)";
        }
    }
}