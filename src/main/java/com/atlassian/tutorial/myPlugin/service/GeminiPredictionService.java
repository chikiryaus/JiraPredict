package com.atlassian.tutorial.myPlugin.service;

// Удаляем импорты, связанные с DI и Spring Scanner
// import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
// import javax.inject.Inject;
// import javax.inject.Named;

// Существующие импорты
import com.atlassian.jira.bc.issue.search.SearchService; // Нужен для типа
import com.atlassian.jira.component.ComponentAccessor; // Будем использовать для получения SearchService
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.search.SearchException;
import com.atlassian.jira.issue.search.SearchResults;
import com.atlassian.jira.jql.builder.JqlQueryBuilder;
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

// Убираем @Named
public class GeminiPredictionService {

    private static final Logger log = LoggerFactory.getLogger(GeminiPredictionService.class);
    private static final String HARDCODED_GEMINI_API_KEY = "AIzaSyB_DYKCaxTIKm9XDZSyRtYAZWeRsyd0n4A"; // ЗАМЕНИТЕ ЭТО!
    private static final String GEMINI_API_URL_BASE = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key="; // Gemini Pro
    private static final int MAX_EXAMPLE_ISSUES = 5;
    private static final String COMPLEXITY_FIELD_ID = "customfield_10000"; // ЗАМЕНИТЕ ЭТО!

    private final String apiKey;

    // Конструктор теперь не принимает SearchService
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

        // Получаем SearchService через ComponentAccessor здесь
        SearchService searchService = ComponentAccessor.getOSGiComponentInstanceOfType(SearchService.class);
        if (searchService == null) {
            log.error("SearchService is null! Cannot search for similar issues.");
            result.put("prediction", "Ошибка: Сервис поиска Jira недоступен.");
            // Промпт все равно будет сгенерирован, но без примеров
            String promptWithoutExamples = buildPromptForIssueWithExamples(currentIssue, new ArrayList<>()); // Пустой список примеров
            result.put("prompt", promptWithoutExamples);
            // Можно сразу попытаться отправить запрос к Gemini только с текущей задачей
            // или вернуть ошибку, если примеры критичны. Пока отправляем без примеров.
            // ... (код вызова Gemini API с promptWithoutExamples) ...
            return sendToGemini(promptWithoutExamples, result); // Вынесем отправку в отдельный метод
        }


        List<Issue> exampleIssues = findSimilarResolvedIssues(currentIssue, searchService); // Передаем searchService
        String prompt = buildPromptForIssueWithExamples(currentIssue, exampleIssues);
        log.debug("Final prompt for Gemini (with examples): {}", prompt);
        result.put("prompt", prompt);

        return sendToGemini(prompt, result); // Вызов вынесенного метода
    }

    // Новый метод для инкапсуляции логики отправки запроса и обработки ответа
    private Map<String, String> sendToGemini(String prompt, Map<String, String> resultAccumulator) {
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
        log.debug("Request body for Gemini: {}", requestBody);

        try {
            URL url = new URL(GEMINI_API_URL_BASE + this.apiKey);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(45000);

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


    // Метод findSimilarResolvedIssues теперь принимает SearchService как параметр
    private List<Issue> findSimilarResolvedIssues(Issue currentIssue, SearchService searchService) {
        List<Issue> examples = new ArrayList<>();
        ApplicationUser searchUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser();
        if (searchUser == null) {
            searchUser = ComponentAccessor.getUserManager().getUserByName("admin"); // Запасной вариант
        }
        if (searchUser == null) {
            log.error("Cannot find a user to perform search for similar issues. Returning empty example list.");
            return examples;
        }

        Object currentComplexityValue = null;
        CustomField complexityField = ComponentAccessor.getCustomFieldManager().getCustomFieldObject(COMPLEXITY_FIELD_ID);
        if (complexityField != null) {
            currentComplexityValue = currentIssue.getCustomFieldValue(complexityField);
        } else {
            log.warn("Complexity field with ID '{}' not found. Cannot search by complexity.", COMPLEXITY_FIELD_ID);
        }

        // Запрос 1
        if (currentComplexityValue != null && examples.size() < MAX_EXAMPLE_ISSUES) {
            try {
                JqlQueryBuilder builder = JqlQueryBuilder.newBuilder();
                builder.where()
                        .project(currentIssue.getProjectObject().getId())
                        .and().customField(complexityField.getIdAsLong()).eq(currentComplexityValue.toString()) // Сравнение как строка
                        .and().addStringCondition("resolutiondate", "IS NOT EMPTY") // Убедимся, что это правильно для JqlQueryBuilder
                        .and().field("key").notEq(currentIssue.getKey());
                builder.orderBy().resolutionDate(SortOrder.DESC);

                SearchResults results = searchService.search(searchUser, builder.buildQuery(), new PagerFilter(MAX_EXAMPLE_ISSUES - examples.size()));
                if (results != null) examples.addAll(results.getIssues());
                log.debug("Found {} examples (same complexity & project). Total examples: {}", (results != null ? results.getIssues().size() : 0), examples.size());
            } catch (SearchException e) {
                log.error("Error searching for similar issues (same complexity & project): ", e);
            } catch (NumberFormatException nfe) {
                log.error("Error parsing complexity value for JQL (same complexity & project). Value was: {}", currentComplexityValue, nfe);
            }
        }

        // Запрос 2
        if (examples.size() < MAX_EXAMPLE_ISSUES && currentComplexityValue != null) {
            try {
                int limit = MAX_EXAMPLE_ISSUES - examples.size();
                if (limit > 0) {
                    JqlQueryBuilder builder = JqlQueryBuilder.newBuilder();
                    List<String> excludeKeys = new ArrayList<>();
                    excludeKeys.add(currentIssue.getKey());
                    for (Issue ex : examples) { excludeKeys.add(ex.getKey()); }

                    builder.where()
                            .customField(complexityField.getIdAsLong()).eq(currentComplexityValue.toString())
                            .and().addStringCondition("resolutiondate", "IS NOT EMPTY")
                            .and().field("key").notIn(excludeKeys.toArray(new String[0]));
                    builder.orderBy().resolutionDate(SortOrder.DESC);

                    SearchResults results = searchService.search(searchUser, builder.buildQuery(), new PagerFilter(limit));
                    if (results != null) examples.addAll(results.getIssues());
                    log.debug("Found {} additional examples (same complexity anywhere). Total examples: {}", (results != null ? results.getIssues().size() : 0), examples.size());
                }
            } catch (SearchException e) {
                log.error("Error searching for similar issues (same complexity anywhere): ", e);
            } catch (NumberFormatException nfe) {
                log.error("Error parsing complexity value for JQL (same complexity anywhere). Value was: {}", currentComplexityValue, nfe);
            }
        }

        // Запрос 3
        if (examples.size() < MAX_EXAMPLE_ISSUES) {
            try {
                int limit = MAX_EXAMPLE_ISSUES - examples.size();
                if (limit > 0) {
                    JqlQueryBuilder builder = JqlQueryBuilder.newBuilder();
                    List<String> excludeKeys = new ArrayList<>();
                    excludeKeys.add(currentIssue.getKey());
                    for (Issue ex : examples) { excludeKeys.add(ex.getKey()); }

                    builder.where()
                            .project(currentIssue.getProjectObject().getId())
                            .and().addStringCondition("resolutiondate", "IS NOT EMPTY")
                            .and().field("key").notIn(excludeKeys.toArray(new String[0]));
                    builder.orderBy().resolutionDate(SortOrder.DESC);

                    SearchResults results = searchService.search(searchUser, builder.buildQuery(), new PagerFilter(limit));
                    if (results != null) examples.addAll(results.getIssues());
                    log.debug("Found {} additional examples (same project). Total examples: {}", (results != null ? results.getIssues().size() : 0), examples.size());
                }
            } catch (SearchException e) {
                log.error("Error searching for similar issues (same project): ", e);
            }
        }
        log.info("Total similar examples collected: {}", examples.size());
        return examples;
    }

    // Метод buildPromptForIssueWithExamples остается таким же
    private String buildPromptForIssueWithExamples(Issue currentIssue, List<Issue> exampleIssues) {
        // ... (код тот же, что и в вашем предыдущем примере) ...
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("Ты - эксперт по оценке времени выполнения задач в Jira. Твоя задача - на основе предоставленной информации о задаче дать краткий прогноз времени, необходимого для ее полного выполнения. Пожалуйста, ответь только предполагаемым временем (например, '2-3 дня', 'около 4 часов', '1 неделя', '3-5 рабочих дней'). Не добавляй никаких других объяснений, извинений или вводных фраз.\n\n");
        promptBuilder.append("--- Информация об оцениваемой задаче ---\n");
        promptBuilder.append("Заголовок: ").append(currentIssue.getSummary()).append("\n");
        if (currentIssue.getDescription() != null && !currentIssue.getDescription().trim().isEmpty()) {
            String descriptionText = currentIssue.getDescription().replaceAll("<[^>]*>", "").trim();
            promptBuilder.append("Описание: ").append(descriptionText.substring(0, Math.min(descriptionText.length(), 700))).append(descriptionText.length() > 700 ? "...\n" : "\n");
        }
        promptBuilder.append("Тип задачи: ").append(currentIssue.getIssueType().getName()).append("\n");
        if (currentIssue.getPriority() != null) {
            promptBuilder.append("Приоритет: ").append(currentIssue.getPriority().getName()).append("\n");
        }
        promptBuilder.append("Проект: ").append(currentIssue.getProjectObject().getName()).append("\n");

        CustomField complexityField = ComponentAccessor.getCustomFieldManager().getCustomFieldObject(COMPLEXITY_FIELD_ID);
        Object currentComplexityValue = null;
        if (complexityField != null) {
            currentComplexityValue = currentIssue.getCustomFieldValue(complexityField);
            promptBuilder.append("Уровень сложности (от 1 до 10): ").append(currentComplexityValue != null ? currentComplexityValue.toString() : "Не указан").append("\n");
        } else {
            promptBuilder.append("Уровень сложности (от 1 до 10): Поле не найдено\n");
        }

        if (exampleIssues != null && !exampleIssues.isEmpty()) {
            promptBuilder.append("\n--- Примеры похожих решенных задач (для контекста) ---\n");
            int count = 0;
            for (Issue example : exampleIssues) {
                count++;
                promptBuilder.append("\nПример ").append(count).append(":\n");
                promptBuilder.append("  Заголовок: ").append(example.getSummary().substring(0, Math.min(example.getSummary().length(), 100))).append(example.getSummary().length() > 100 ? "...\n" : "\n");
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
                if (count >= MAX_EXAMPLE_ISSUES) break;
            }
        }
        promptBuilder.append("\n\n--- Запрос ---\n");
        promptBuilder.append("Основываясь на информации об оцениваемой задаче и, если есть, примерах выше, дай краткий прогноз времени, необходимого для ее выполнения. Ответь только предполагаемым временем (например, '2-3 дня', 'около 4 часов', '1 неделя', '3-5 рабочих дней'). Не добавляй никаких других объяснений, извинений или вводных фраз.");
        promptBuilder.append("\nТвой прогноз времени выполнения (только оценка времени):");
        return promptBuilder.toString();
    }

    // Метод parseGeminiResponse остается таким же
    private String parseGeminiResponse(String jsonResponse) {
        // ... (код тот же, что и в вашем предыдущем примере) ...
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