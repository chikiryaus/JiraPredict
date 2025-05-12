package com.atlassian.tutorial.myPlugin.service;

import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.component.ComponentAccessor;

import org.json.JSONObject;
import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class GeminiPredictionService {

    private static final Logger log = LoggerFactory.getLogger(GeminiPredictionService.class);
    private static final String GEMINI_API_KEY_SETTING = "com.atlassian.tutorial.myPlugin.geminiApiKey"; // Для настроек
    // ВРЕМЕННЫЙ ХАРДКОД КЛЮЧА ДЛЯ ТЕСТИРОВАНИЯ! НЕ ИСПОЛЬЗОВАТЬ В ПРОДАКШЕНЕ!
    private static final String HARDCODED_GEMINI_API_KEY = "AIzaSyB_DYKCaxTIKm9XDZSyRtYAZWeRsyd0n4A";
    private static final String GEMINI_API_URL_BASE = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=";

    private String apiKey;

    public GeminiPredictionService() {
        // Для MVP используем захардкоженный ключ, чтобы не усложнять настройками
        // В будущем нужно будет реализовать загрузку из PluginSettings
        this.apiKey = HARDCODED_GEMINI_API_KEY;
        if (this.apiKey == null || this.apiKey.trim().isEmpty() || "ВАШ_GEMINI_API_КЛЮЧ_ЗДЕСЬ".equals(this.apiKey)) {
            log.warn("Gemini API Key is not configured or using placeholder! Please set a valid key.");
        }
        // loadApiKeyFromSettings(); // Это для будущего, когда будет UI настроек
    }

    // Метод для будущего, когда будет UI для настроек ключа
    private void loadApiKeyFromSettings() {
        PluginSettingsFactory pluginSettingsFactory = ComponentAccessor.getOSGiComponentInstanceOfType(PluginSettingsFactory.class);
        if (pluginSettingsFactory != null) {
            PluginSettings settings = pluginSettingsFactory.createGlobalSettings();
            this.apiKey = (String) settings.get(GEMINI_API_KEY_SETTING);
            if (this.apiKey == null || this.apiKey.trim().isEmpty()) {
                log.warn("Gemini API Key is not configured in plugin settings!");
            }
        } else {
            log.error("PluginSettingsFactory is null! Cannot load API key from settings.");
            this.apiKey = null;
        }
    }

    public String getPredictionFromGemini(Issue issue) {
        if (this.apiKey == null || this.apiKey.trim().isEmpty() || "ВАШ_GEMINI_API_КЛЮЧ_ЗДЕСЬ".equals(this.apiKey)) {
            return "Ошибка: API ключ Gemini не настроен.";
        }

        if (issue == null) {
            log.warn("Issue object is null in getPredictionFromGemini.");
            return "Ошибка: Объект задачи не предоставлен.";
        }

        String prompt = buildPromptForIssue(issue);
        log.debug("Prompt for Gemini: {}", prompt);

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

        // Можно добавить "safetySettings", если API требует или для фильтрации контента
        // JSONArray safetySettingsArray = new JSONArray();
        // JSONObject safetySetting = new JSONObject();
        // safetySetting.put("category", "HARM_CATEGORY_HARASSMENT");
        // safetySetting.put("threshold", "BLOCK_NONE"); // Пример, настройте по необходимости
        // safetySettingsArray.put(safetySetting);
        // // ... другие safety settings ...
        // requestBodyJson.put("safetySettings", safetySettingsArray);


        String requestBody = requestBodyJson.toString();
        log.debug("Request body for Gemini: {}", requestBody);

        try {
            URL url = new URL(GEMINI_API_URL_BASE + this.apiKey);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8"); // Добавил charset
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000); // Таймаут на соединение 15 сек
            conn.setReadTimeout(30000);    // Таймаут на чтение ответа 30 сек


            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            log.info("Gemini API Response Code: {}", responseCode); // Изменил на info для большей видимости

            StringBuilder responseContent = new StringBuilder();
            BufferedReader reader = null;

            if (responseCode >= 200 && responseCode < 300) { // HTTP_OK и другие успешные
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
                return parseGeminiResponse(rawResponse);
            } else {
                log.error("Gemini API Error Response ({}): {}", responseCode, rawResponse);
                return "Ошибка Gemini API: " + responseCode + " - " + rawResponse.substring(0, Math.min(rawResponse.length(), 150)) + "...";
            }

        } catch (java.net.SocketTimeoutException e) {
            log.error("Timeout while calling Gemini API: ", e);
            return "Ошибка: Таймаут при обращении к Gemini.";
        }
        catch (Exception e) {
            log.error("Exception while calling Gemini API: ", e);
            return "Ошибка при обращении к Gemini: " + e.getMessage();
        }
    }

    private String buildPromptForIssue(Issue issue) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("Ты - эксперт по оценке времени выполнения задач в Jira. Твоя задача - на основе предоставленной информации о задаче дать краткий прогноз времени, необходимого для ее полного выполнения. Пожалуйста, ответь только предполагаемым временем (например, '2-3 дня', 'около 4 часов', '1 неделя', '3-5 рабочих дней'). Не добавляй никаких других объяснений, извинений или вводных фраз.\n\n");
        promptBuilder.append("Информация о задаче:\n");
        promptBuilder.append("Заголовок: ").append(issue.getSummary()).append("\n");
        if (issue.getDescription() != null && !issue.getDescription().trim().isEmpty()) {
            // Убираем HTML теги из описания для LLM, если они там могут быть
            String descriptionText = issue.getDescription().replaceAll("<[^>]*>", "").trim();
            promptBuilder.append("Описание: ").append(descriptionText.substring(0, Math.min(descriptionText.length(), 700))).append(descriptionText.length() > 700 ? "...\n" : "\n");
        }
        promptBuilder.append("Тип задачи: ").append(issue.getIssueType().getName()).append("\n");
        if (issue.getPriority() != null) {
            promptBuilder.append("Приоритет: ").append(issue.getPriority().getName()).append("\n");
        }
        promptBuilder.append("Проект: ").append(issue.getProjectObject().getName()).append("\n");

        // ЗАМЕНИТЕ "customfield_10001" на ID вашего поля "Сложность"!
        CustomField complexityField = ComponentAccessor.getCustomFieldManager().getCustomFieldObject("customfield_10001");
        if (complexityField != null) {
            Object complexityValue = issue.getCustomFieldValue(complexityField);
            if (complexityValue != null) {
                promptBuilder.append("Уровень сложности (от 1 до 10): ").append(complexityValue.toString()).append("\n");
            }
        }
        // Можно добавить другие поля, если они важны, например, story points
        // CustomField storyPointsField = ComponentAccessor.getCustomFieldManager().getCustomFieldObject("customfield_XXXXX"); // ID поля Story Points
        // if (storyPointsField != null) {
        //     Object spValue = issue.getCustomFieldValue(storyPointsField);
        //     if (spValue != null) {
        //         promptBuilder.append("Story Points: ").append(spValue.toString()).append("\n");
        //     }
        // }

        promptBuilder.append("\nТвой прогноз времени выполнения (только оценка времени):");
        return promptBuilder.toString();
    }

    private String parseGeminiResponse(String jsonResponse) {
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
            // Если ответ не удалось распарсить или он пустой
            if (responseJson.has("promptFeedback") && responseJson.getJSONObject("promptFeedback").has("blockReason")) {
                String reason = responseJson.getJSONObject("promptFeedback").getString("blockReason");
                log.warn("Gemini response blocked, reason: {}", reason);
                return "Ответ Gemini заблокирован: " + reason;
            }

            log.warn("Could not parse Gemini response structure or response is empty: {}", jsonResponse);
            return "Не удалось извлечь текст из ответа Gemini.";
        } catch (Exception e) {
            log.error("Error parsing Gemini JSON response: ", e);
            return "Ошибка парсинга ответа Gemini.";
        }
    }
}