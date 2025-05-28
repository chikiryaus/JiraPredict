package com.atlassian.tutorial.myPlugin.servlet;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.sal.api.auth.LoginUriProvider;
import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.templaterenderer.TemplateRenderer;
import com.atlassian.tutorial.myPlugin.service.GeminiPredictionService; // Для доступа к ключу настройки

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

public class GeminiConfigServlet extends HttpServlet {
    private static final Logger log = LoggerFactory.getLogger(GeminiConfigServlet.class);

    // Используем ComponentAccessor для получения зависимостей, так как сервлет не управляется Spring Scanner напрямую так же, как @Named компоненты
    private final UserManager userManager;
    private final LoginUriProvider loginUriProvider;
    private final TemplateRenderer renderer;
    private final PluginSettingsFactory pluginSettingsFactory;

    public GeminiConfigServlet() {
        // Получаем зависимости через ComponentAccessor
        // Это общепринятый способ для сервлетов в плагинах Atlassian, если они не регистрируются как Spring-бины
        this.userManager = ComponentAccessor.getOSGiComponentInstanceOfType(UserManager.class);
        this.loginUriProvider = ComponentAccessor.getOSGiComponentInstanceOfType(LoginUriProvider.class);
        this.renderer = ComponentAccessor.getOSGiComponentInstanceOfType(TemplateRenderer.class);
        this.pluginSettingsFactory = ComponentAccessor.getOSGiComponentInstanceOfType(PluginSettingsFactory.class);

        if (userManager == null) log.error("UserManager is null in GeminiConfigServlet constructor!");
        if (loginUriProvider == null) log.error("LoginUriProvider is null in GeminiConfigServlet constructor!");
        if (renderer == null) log.error("TemplateRenderer is null in GeminiConfigServlet constructor!");
        if (pluginSettingsFactory == null) log.error("PluginSettingsFactory is null in GeminiConfigServlet constructor!");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // Проверка прав администратора
        String username = userManager.getRemoteUsername(req);
        if (username == null || !userManager.isSystemAdmin(username)) {
            redirectToLogin(req, resp);
            return;
        }

        Map<String, Object> context = new HashMap<>();
        PluginSettings settings = pluginSettingsFactory.createGlobalSettings();
        String currentApiKey = (String) settings.get(GeminiPredictionService.GEMINI_API_KEY_PLUGIN_SETTING);
        context.put("apiKey", currentApiKey != null ? currentApiKey : "");
        context.put("action", req.getContextPath() + "/plugins/servlet/gemini-config"); // URL для POST запроса

        resp.setContentType("text/html;charset=utf-8");
        try {
            renderer.render("templates/admin/gemini-config-page.vm", context, resp.getWriter());
        } catch (Exception e) {
            log.error("Error rendering Gemini config page", e);
            resp.getWriter().write("Error rendering configuration page: " + e.getMessage());
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // Проверка прав администратора
        String username = userManager.getRemoteUsername(req);
        if (username == null || !userManager.isSystemAdmin(username)) {
            redirectToLogin(req, resp);
            return;
        }

        String newApiKey = req.getParameter("apiKey");
        PluginSettings settings = pluginSettingsFactory.createGlobalSettings();
        if (newApiKey != null) {
            settings.put(GeminiPredictionService.GEMINI_API_KEY_PLUGIN_SETTING, newApiKey.trim());
            log.info("Gemini API Key updated by user: {}", username);

            // Опционально: уведомить GeminiPredictionService об обновлении ключа, если он кэширует
            // Можно сделать через событие или прямой вызов, если сервис доступен.
            // Для простоты пока оставим так - сервис будет перечитывать ключ при следующем вызове.
            GeminiPredictionService geminiService = ComponentAccessor.getOSGiComponentInstanceOfType(GeminiPredictionService.class);
            if(geminiService != null) {
                geminiService.refreshApiKey(); // Добавим метод для обновления
            }

        } else {
            settings.remove(GeminiPredictionService.GEMINI_API_KEY_PLUGIN_SETTING);
            log.info("Gemini API Key removed by user: {}", username);
        }

        // Перенаправляем обратно на страницу конфигурации с сообщением об успехе (или используем velocity для этого)
        // Для простоты пока просто перенаправляем
        resp.sendRedirect(req.getContextPath() + "/plugins/servlet/gemini-config?saved=true");
    }

    private void redirectToLogin(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.sendRedirect(loginUriProvider.getLoginUri(getUri(request)).toASCIIString());
    }

    private URI getUri(HttpServletRequest request) {
        StringBuffer builder = request.getRequestURL();
        if (request.getQueryString() != null) {
            builder.append("?");
            builder.append(request.getQueryString());
        }
        return URI.create(builder.toString());
    }
}