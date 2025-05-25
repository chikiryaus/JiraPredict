package com.atlassian.tutorial.myPlugin.service;

// ... (все предыдущие импорты остаются) ...
import com.atlassian.jira.bc.issue.search.SearchService;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.Issue;
// import com.atlassian.jira.issue.fields.CustomField; // Больше не нужен здесь, если не используем сложность для поиска примеров
import com.atlassian.jira.issue.search.SearchException;
import com.atlassian.jira.issue.search.SearchResults;
import com.atlassian.jira.jql.builder.JqlQueryBuilder;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.web.bean.PagerFilter;
import com.atlassian.query.order.SortOrder;

// ... (остальные импорты JSON, SLF4J, Java Util и т.д.) ...
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
// import java.util.stream.Collectors; // Больше не нужен, если нет сложного исключения ключей


public class GeminiPredictionService {

    private static final Logger log = LoggerFactory.getLogger(GeminiPredictionService.class);
    private static final String HARDCODED_GEMINI_API_KEY = "AIzaSyB_DYKCaxTIKm9XDZSyRtYAZWeRsyd0n4A"; // ЗАМЕНИТЕ ЭТО!
    private static final String GEMINI_API_URL_BASE = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key="; // Gemini Pro
    private static final int TARGET_EXAMPLE_ISSUES_COUNT = 10; // Целевое количество примеров
    private static final String COMPLEXITY_FIELD_ID = "customfield_10000"; // ЗАМЕНИТЕ ЭТО!

    private final String apiKey;

    public GeminiPredictionService() {
        this.apiKey = HARDCODED_GEMINI_API_KEY;
        if ("ВАШ_GEMINI_API_КЛЮЧ_ЗДЕСЬ".equals(this.apiKey) || this.apiKey == null || this.apiKey.trim().isEmpty()) {
            log.warn("Gemini API Key is using a placeholder or is not configured! Please set a valid key directly in the HARDCODED_GEMINI_API_KEY constant.");
        } else {
            log.info("GeminiPredictionService initialized with a hardcoded API key.");
        }
    }

    public Map<String, String> getPredictionFromGemini(Issue currentIssue) {
        Map<String, String> result = new HashMap<>();
        result.put("prompt", "");
        result.put("prediction", "");

        if ("ВАШ_GEMINI_API_КЛЮЧ_ЗДЕСЬ".equals(this.apiKey) || this.apiKey == null || this.apiKey.trim().isEmpty()) {
            result.put("prediction", "Ошибка: API ключ Gemini не настроен (используется плейсхолдер или пустой).");
            return result;
        }
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

        // Используем упрощенный метод поиска
        List<Issue> exampleIssues = findResolvedIssuesInSameProject(currentIssue, searchService);
        String prompt = buildPromptForIssueWithExamples(currentIssue, exampleIssues);
        log.info("Final prompt for Gemini (length: {} chars, {} examples): {}", prompt.length(), exampleIssues.size(), prompt.substring(0, Math.min(prompt.length(), 200)) + "...");
        result.put("prompt", prompt);

        return sendToGemini(prompt, result);
    }

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

    /**
     * Ищет до TARGET_EXAMPLE_ISSUES_COUNT любых решенных задач из того же проекта.
     */
    private List<Issue> findResolvedIssuesInSameProject(Issue currentIssue, SearchService searchService) {
        List<Issue> exampleIssues = new ArrayList<>();
        ApplicationUser searchUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser();
        if (searchUser == null) {
            searchUser = ComponentAccessor.getUserManager().getUserByName("admin");
        }
        if (searchUser == null) {
            log.error("Cannot find a user to perform search for example issues. Returning empty list.");
            return exampleIssues;
        }
        log.info("Searching for example issues in project '{}' as user: {}", currentIssue.getProjectObject().getKey(), searchUser.getName());

        // Формируем JQL как строку
        // Важно правильно экранировать значения, если они приходят извне, но здесь projectKey и currentIssue.getKey() обычно безопасны.
        String projectKey = currentIssue.getProjectObject().getKey();
        String currentIssueKey = currentIssue.getKey();

        // JQL для поиска до 10 решенных задач в том же проекте, исключая текущую, отсортированных по дате решения
        String jqlQuery = String.format("project = \"%s\" AND resolutiondate IS NOT EMPTY AND key != \"%s\" ORDER BY resolutiondate DESC",
                projectKey.replace("\"", "\\\""), // Экранируем кавычки в ключе проекта, если они там могут быть
                currentIssueKey.replace("\"", "\\\"")); // Экранируем кавычки в ключе текущей задачи

        log.info("JQL for examples (same project, resolved, string formatted): {}", jqlQuery);

        SearchService.ParseResult parseResult = searchService.parseQuery(searchUser, jqlQuery);
        if (!parseResult.isValid()) {
            log.error("Invalid JQL query: {}. Errors: {}", jqlQuery, parseResult.getErrors().getErrorMessages());
            return exampleIssues;
        }

        try {
            // Используем PagerFilter для ограничения количества результатов
            SearchResults searchResults = searchService.search(searchUser, parseResult.getQuery(), new PagerFilter(TARGET_EXAMPLE_ISSUES_COUNT));

            if (searchResults != null) {
                exampleIssues.addAll(searchResults.getIssues());
                log.info("Found {} example issues from project '{}' using string JQL. Target: {}",
                        exampleIssues.size(), projectKey, TARGET_EXAMPLE_ISSUES_COUNT);
            } else {
                log.warn("Search for example issues returned null results using string JQL.");
            }
        } catch (SearchException e) {
            log.error("SearchException during findResolvedIssuesInSameProjectByStringJql: {}", e.getMessage());
        } catch (Exception e) { // Ловим более общие исключения
            log.error("Exception during findResolvedIssuesInSameProjectByStringJql: {}", e.getMessage(), e);
        }

        log.info("Total examples collected for prompt (using string JQL): {}", exampleIssues.size());
        return exampleIssues;
    }


    // Метод buildPromptForIssueWithExamples остается почти таким же.
    // Единственное, что поле "Сложность" для примеров теперь всегда будет извлекаться,
    // даже если мы не фильтровали по нему. Это нормально, LLM может использовать эту информацию.
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

        // Всегда пытаемся получить сложность для текущей задачи
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

                // Всегда пытаемся получить сложность для примера, если поле существует
                Object exampleComplexity = null;
                if (complexityField != null) { // Используем тот же complexityField, что и для текущей задачи
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
        promptBuilder.append("Основываясь на информации об оцениваемой задаче и, если есть, примерах выше, дай краткий прогноз времени, необходимого для ее выполнения. Ответь только предполагаемым временем (например, '2-3 дня', 'около 4 часов', '1 неделя', '3-5 рабочих дней'). Не добавляй никаких других объяснений, извинений или вводных фраз.");
        promptBuilder.append("\nТвой прогноз времени выполнения (только оценка времени):");
        return promptBuilder.toString();
    }

    // Метод parseGeminiResponse остается таким же
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
}