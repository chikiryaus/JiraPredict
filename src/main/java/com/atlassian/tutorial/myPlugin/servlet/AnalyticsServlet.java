


import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import com.atlassian.templaterenderer.TemplateRenderer;
import com.atlassian.tutorial.myPlugin.service.GeminiPredictionService;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AnalyticsServlet extends HttpServlet {
    private final TemplateRenderer renderer;
    private final PluginSettingsFactory pluginSettingsFactory;

    public AnalyticsServlet() {
        this.renderer = ComponentAccessor.getOSGiComponentInstanceOfType(TemplateRenderer.class);
        this.pluginSettingsFactory = ComponentAccessor.getOSGiComponentInstanceOfType(PluginSettingsFactory.class);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        PluginSettings settings = pluginSettingsFactory.createGlobalSettings();
        String history = (String) settings.get(GeminiPredictionService.PREDICTION_HISTORY_SETTING);

        List<Map<String, Object>> chartData = new ArrayList<>();
        IssueManager issueManager = ComponentAccessor.getIssueManager();

        if (history != null && !history.isEmpty()) {
            String[] entries = history.split(";");
            // Берем последние 10-15 прогнозов для графика
            int start = Math.max(0, entries.length - 15);
            for (int i = start; i < entries.length; i++) {
                String[] parts = entries[i].split(":", 2);
                if (parts.length < 2) continue;

                String issueKey = parts[0];
                String predictedText = parts[1];

                Issue issue = issueManager.getIssueObject(issueKey);
                if (issue != null && issue.getResolutionDate() != null) {
                    // Считаем реальное время в часах
                    long diff = issue.getResolutionDate().getTime() - issue.getCreated().getTime();
                    double actualHours = (double) diff / (1000 * 60 * 60);

                    // Парсим прогноз из текста в число
                    double predictedHours = parseHoursFromText(predictedText);

                    Map<String, Object> dataPoint = new HashMap<>();
                    dataPoint.put("key", issueKey);
                    dataPoint.put("actual", Math.round(actualHours * 10.0) / 10.0); // Округление
                    dataPoint.put("predicted", predictedHours);
                    chartData.add(dataPoint);
                }
            }
        }

        Map<String, Object> context = new HashMap<>();
        context.put("chartData", chartData);
        resp.setContentType("text/html;charset=utf-8");
        renderer.render("templates/analytics-page.vm", context, resp.getWriter());
    }

    /**
     * Пытается извлечь число часов из ответов типа "3 часа", "1-2 дня", "около 5ч"
     */
    private double parseHoursFromText(String text) {
        try {
            text = text.toLowerCase();
            Pattern pattern = Pattern.compile("(\\d+(?:[.,]\\d+)?)");
            Matcher matcher = pattern.matcher(text);

            if (matcher.find()) {
                double value = Double.parseDouble(matcher.group(1).replace(",", "."));

                if (text.contains("ден") || text.contains("day")) {
                    return value * 8; // Считаем рабочий день как 8 часов
                }
                if (text.contains("нед") || text.contains("week")) {
                    return value * 40; // Рабочая неделя
                }
                return value; // По умолчанию часы
            }
        } catch (Exception e) {
            return 0;
        }
        return 0;
    }
}