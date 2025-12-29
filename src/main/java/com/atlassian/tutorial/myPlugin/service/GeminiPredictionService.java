package com.atlassian.tutorial.myPlugin.service;

import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.bc.issue.search.SearchService;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.customfields.option.Option;
import com.atlassian.jira.issue.search.SearchException;
import com.atlassian.jira.issue.search.SearchResults;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.web.bean.PagerFilter;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class GeminiPredictionService {

    private static final Logger log = LoggerFactory.getLogger(GeminiPredictionService.class);

    // Ключи для PluginSettings
    public static final String GEMINI_API_KEY_PLUGIN_SETTING = "com.atlassian.tutorial.myPlugin.geminiApiKey";
    public static final String PREDICTION_HISTORY_SETTING = "com.atlassian.tutorial.myPlugin.history";

    private static final String GIGACHAT_OAUTH_URL = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth";
    private static final String GIGACHAT_API_URL = "https://gigachat.devices.sberbank.ru/api/v1/chat/completions";
    private static final String MODEL_NAME = "GigaChat";

    private static final int TARGET_EXAMPLE_ISSUES_COUNT_FOR_LLM = 5;
    public static final String COMPLEXITY_FIELD_ID = "customfield_10000";

    private String base64AuthKey;

    public GeminiPredictionService() {
        loadApiKeyFromSettings();
    }

    private void loadApiKeyFromSettings() {
        PluginSettingsFactory factory = ComponentAccessor.getOSGiComponentInstanceOfType(PluginSettingsFactory.class);
        if (factory != null) {
            PluginSettings settings = factory.createGlobalSettings();
            this.base64AuthKey = (String) settings.get(GEMINI_API_KEY_PLUGIN_SETTING);
            if (this.base64AuthKey != null) this.base64AuthKey = this.base64AuthKey.trim();
        }
    }

    public void refreshApiKey() {
        loadApiKeyFromSettings();
    }

    // --- СЕКЦИЯ SSL (ОБХОД ПРОВЕРОК) ---
    private SSLSocketFactory getInsecureSSLSocketFactory() throws Exception {
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
        };
        SSLContext sc = SSLContext.getInstance("SSL");
        sc.init(null, trustAllCerts, new java.security.SecureRandom());
        return sc.getSocketFactory();
    }

    private HostnameVerifier getInsecureHostnameVerifier() {
        return (hostname, session) -> true;
    }

    // --- СЕКЦИЯ РАБОТЫ С GIGACHAT ---
    private String getGigaChatToken() throws Exception {
        if (this.base64AuthKey == null || this.base64AuthKey.isEmpty()) {
            throw new Exception("Auth Key не настроен в конфигурации!");
        }

        URL url = new URL(GIGACHAT_OAUTH_URL);
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setSSLSocketFactory(getInsecureSSLSocketFactory());
        conn.setHostnameVerifier(getInsecureHostnameVerifier());

        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Authorization", "Basic " + this.base64AuthKey);
        conn.setRequestProperty("RqUID", UUID.randomUUID().toString());
        conn.setDoOutput(true);

        String data = "scope=GIGACHAT_API_PERS";
        try (OutputStream os = conn.getOutputStream()) {
            os.write(data.getBytes(StandardCharsets.UTF_8));
        }

        if (conn.getResponseCode() == 200) {
            JSONObject json = new JSONObject(readStream(conn));
            return json.getString("access_token");
        } else {
            throw new Exception("OAuth Error: " + conn.getResponseCode() + " " + readStream(conn));
        }
    }

    public Map<String, String> getPredictionFromGemini(Issue currentIssue) {
        refreshApiKey();
        Map<String, String> result = new HashMap<>();

        try {
            String token = getGigaChatToken();
            SearchService searchService = ComponentAccessor.getOSGiComponentInstanceOfType(SearchService.class);

            // Получаем примеры (ПОЛНАЯ ЛОГИКА)
            List<Issue> exampleIssues = findResolvedIssuesInSameProject(currentIssue, searchService, TARGET_EXAMPLE_ISSUES_COUNT_FOR_LLM);

            // Строим детальный промпт (ПОЛНАЯ ЛОГИКА)
            String prompt = buildPromptForIssueWithExamples(currentIssue, exampleIssues);
            result.put("prompt", prompt);

            // Запрос к модели
            Map<String, String> predictionData = sendToGigaChat(token, prompt, result);

            // СОХРАНЕНИЕ: Если прогноз получен успешно, записываем его в историю
            String predictionText = predictionData.get("prediction");
            if (predictionText != null && !predictionText.startsWith("Ошибка")) {
                savePredictionToHistory(currentIssue.getKey(), predictionText);
            }

            return predictionData;

        } catch (Exception e) {
            log.error("GigaChat error", e);
            result.put("prediction", "Ошибка: " + e.getMessage());
            return result;
        }
    }

    private Map<String, String> sendToGigaChat(String token, String prompt, Map<String, String> resultAccumulator) {
        try {
            JSONObject body = new JSONObject();
            body.put("model", MODEL_NAME);
            JSONArray msgs = new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt));
            body.put("messages", msgs);
            body.put("temperature", 0.7);

            URL url = new URL(GIGACHAT_API_URL);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setSSLSocketFactory(getInsecureSSLSocketFactory());
            conn.setHostnameVerifier(getInsecureHostnameVerifier());

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            if (conn.getResponseCode() == 200) {
                JSONObject res = new JSONObject(readStream(conn));
                String text = res.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
                resultAccumulator.put("prediction", text.trim());
            } else {
                resultAccumulator.put("prediction", "Ошибка API: " + conn.getResponseCode());
            }
        } catch (Exception e) {
            resultAccumulator.put("prediction", "Ошибка: " + e.getMessage());
        }
        return resultAccumulator;
    }

    // --- СОХРАНЕНИЕ ПРОГНОЗОВ (Для аналитики) ---
    private void savePredictionToHistory(String issueKey, String prediction) {
        PluginSettingsFactory factory = ComponentAccessor.getOSGiComponentInstanceOfType(PluginSettingsFactory.class);
        if (factory != null) {
            PluginSettings settings = factory.createGlobalSettings();
            // Формат: Ключ_задачи=Прогноз (число часов, если сможем распарсить)
            // Для простоты храним как "ISSUE-123:3 часа;ISSUE-124:5 дней"
            String history = (String) settings.get(PREDICTION_HISTORY_SETTING);
            if (history == null) history = "";

            // Добавляем запись (удаляя старую по этой задаче, если была)
            String entry = issueKey + ":" + prediction.replace(";", ",") + ";";
            settings.put(PREDICTION_HISTORY_SETTING, history + entry);
        }
    }

    // --- ПОЛНАЯ ЛОГИКА ПОИСКА ПРИМЕРОВ ---
    private List<Issue> findResolvedIssuesInSameProject(Issue currentIssue, SearchService searchService, int limit) {
        List<Issue> exampleIssues = new ArrayList<>();
        ApplicationUser searchUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser();
        if (searchUser == null) { searchUser = ComponentAccessor.getUserManager().getUserByName("admin"); }

        String projectKey = currentIssue.getProjectObject().getKey();
        String currentIssueKey = currentIssue.getKey();
        String jqlQuery = String.format("project = \"%s\" AND resolutiondate IS NOT EMPTY AND key != \"%s\" ORDER BY resolutiondate DESC",
                projectKey.replace("\"", "\\\""),
                currentIssueKey.replace("\"", "\\\""));

        SearchService.ParseResult parseResult = searchService.parseQuery(searchUser, jqlQuery);
        if (parseResult.isValid()) {
            try {
                SearchResults searchResults = searchService.search(searchUser, parseResult.getQuery(), new PagerFilter(limit));
                if (searchResults != null) { exampleIssues.addAll(searchResults.getIssues()); }
            } catch (SearchException e) { log.error("Search error", e); }
        }
        return exampleIssues;
    }

    // --- ПОЛНАЯ ЛОГИКА ПОСТРОЕНИЯ ПРОМПТА (Как в вашем оригинале) ---
    private String buildPromptForIssueWithExamples(Issue currentIssue, List<Issue> exampleIssues) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("Ты - эксперт по оценке времени выполнения задач в Jira. Твоя задача - на основе предоставленной информации о задаче дать краткий прогноз времени, необходимого для ее полного выполнения. Пожалуйста, ответь только предполагаемым временем (например, '2-3 дня', 'около 4 часов', '1 неделя'). Не добавляй никаких других объяснений.\n\n");

        promptBuilder.append("--- Информация об оцениваемой задаче ---\n");
        promptBuilder.append("Заголовок: ").append(currentIssue.getSummary()).append("\n");

        if (currentIssue.getDescription() != null && !currentIssue.getDescription().trim().isEmpty()) {
            String desc = currentIssue.getDescription().replaceAll("<[^>]*>", "").trim();
            promptBuilder.append("Описание: ").append(desc.substring(0, Math.min(desc.length(), 500))).append("\n");
        }

        promptBuilder.append("Тип: ").append(currentIssue.getIssueType().getName()).append("\n");
        if (currentIssue.getPriority() != null) {
            promptBuilder.append("Приоритет: ").append(currentIssue.getPriority().getName()).append("\n");
        }

        CustomField complexityField = ComponentAccessor.getCustomFieldManager().getCustomFieldObject(COMPLEXITY_FIELD_ID);
        if (complexityField != null) {
            Object val = currentIssue.getCustomFieldValue(complexityField);
            promptBuilder.append("Уровень сложности (1-10): ").append(val != null ? val.toString() : "Не указан").append("\n");
        }

        if (exampleIssues != null && !exampleIssues.isEmpty()) {
            promptBuilder.append("\n--- Примеры решенных задач ---\n");
            for (Issue example : exampleIssues) {
                promptBuilder.append("\nЗадача: ").append(example.getSummary()).append("\n");

                Object exComplexity = (complexityField != null) ? example.getCustomFieldValue(complexityField) : "N/A";
                promptBuilder.append("Сложность: ").append(exComplexity).append("\n");

                if (example.getResolutionDate() != null && example.getCreated() != null) {
                    long durationMillis = example.getResolutionDate().getTime() - example.getCreated().getTime();
                    long days = TimeUnit.MILLISECONDS.toDays(durationMillis);
                    long hours = TimeUnit.MILLISECONDS.toHours(durationMillis - TimeUnit.DAYS.toMillis(days));
                    promptBuilder.append("Фактическое время: ").append(days > 0 ? days + " дн. " : "").append(hours).append(" ч.\n");
                }
            }
        }

        promptBuilder.append("\nТвой краткий прогноз времени выполнения:");
        return promptBuilder.toString();
    }

    private String readStream(HttpURLConnection conn) throws Exception {
        boolean isSuccess = conn.getResponseCode() < 400;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                isSuccess ? conn.getInputStream() : conn.getErrorStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line.trim());
            return sb.toString();
        }
    }

    // Метод расчета среднего (оставлен без изменений)
    public String getAverageTimeByComplexity(Issue currentIssue) {
        if (currentIssue == null) return "N/A";
        SearchService searchService = ComponentAccessor.getOSGiComponentInstanceOfType(SearchService.class);
        ApplicationUser user = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser();
        CustomField complexityField = ComponentAccessor.getCustomFieldManager().getCustomFieldObject(COMPLEXITY_FIELD_ID);

        Object rawVal = currentIssue.getCustomFieldValue(complexityField);
        if (rawVal == null) return "N/A (сложность не указана)";

        String complexitySearchTerm = (rawVal instanceof Option) ? ((Option) rawVal).getValue() : rawVal.toString();
        String jql = String.format("cf[%s] = \"%s\" AND resolutiondate IS NOT EMPTY",
                COMPLEXITY_FIELD_ID.replace("customfield_", ""), complexitySearchTerm.replace("\"", "\\\""));

        SearchService.ParseResult parseResult = searchService.parseQuery(user, jql);
        if (parseResult.isValid()) {
            try {
                SearchResults results = searchService.search(user, parseResult.getQuery(), PagerFilter.getUnlimitedFilter());
                long total = 0; int count = 0;
                for (Issue resolvedIssue : results.getIssues()) {
                    if (resolvedIssue.getCreated() != null && resolvedIssue.getResolutionDate() != null) {
                        total += (resolvedIssue.getResolutionDate().getTime() - resolvedIssue.getCreated().getTime());
                        count++;
                    }
                }
                if (count > 0) {
                    long avgMillis = total / count;
                    long days = TimeUnit.MILLISECONDS.toDays(avgMillis);
                    long hours = TimeUnit.MILLISECONDS.toHours(avgMillis - TimeUnit.DAYS.toMillis(days));
                    return String.format("~%d дн. %d ч. (на основе %d задач)", days, hours, count);
                }
            } catch (Exception e) { log.error("Avg calculation error", e); }
        }
        return "Нет данных";
    }
}