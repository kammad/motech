package org.motechproject.server.web.controller;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.Predicate;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.motechproject.commons.api.CastUtils;
import org.motechproject.osgi.web.ModuleRegistrationData;
import org.motechproject.osgi.web.UIFrameworkService;
import org.motechproject.server.ui.LocaleService;
import org.motechproject.server.web.dto.ModuleConfig;
import org.motechproject.server.web.dto.ModuleMenu;
import org.motechproject.server.web.form.UserInfo;
import org.motechproject.server.web.helper.MenuBuilder;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.commons.lang.StringUtils.EMPTY;
import static org.apache.commons.lang.StringUtils.equalsIgnoreCase;
import static org.apache.commons.lang.StringUtils.isNotBlank;
import static org.springframework.util.CollectionUtils.isEmpty;

@Controller
public class ModuleController {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModuleController.class);

    private static final String NAME_GROUP = "name";
    private static final String DEPENDENCIES_GROUP = "dependencies";
    private static final String MODULE_NAME = String.format(
            "('|\")(?<%s>[^'\"]+)('|\")", NAME_GROUP
    );
    private static final String DEPENDENCIES = String.format(
            "\\[(?<%s>[^\\]]*)\\]", DEPENDENCIES_GROUP
    );
    private static final String REGEXP = String.format(
            "angular\\.module\\(%s(,\\s+%s)?\\)", MODULE_NAME, DEPENDENCIES
    );
    private static final Pattern PATTERN = Pattern.compile(REGEXP);

    private UIFrameworkService uiFrameworkService;
    private LocaleService localeService;
    private BundleContext bundleContext;
    private MenuBuilder menuBuilder;

    @RequestMapping(value = "/module/critical/{moduleName}", method = RequestMethod.GET)
    @ResponseBody
    public String getCriticalMessage(@PathVariable String moduleName) {
        String criticalMessage = null;

        if (isNotBlank(moduleName)) {
            ModuleRegistrationData data = uiFrameworkService.getModuleDataByAngular(moduleName);

            if (null != data) {
                criticalMessage = data.getCriticalMessage();
                uiFrameworkService.moduleBackToNormal(moduleName);
            }
        }

        return criticalMessage;
    }

    @RequestMapping(value = "/module/menu", method = RequestMethod.GET)
    @ResponseBody
    public ModuleMenu getMenu(HttpServletRequest request) {
        String username = getUser(request).getUserName();
        return menuBuilder.buildMenu(username);
    }

    @RequestMapping(value = "/module/config", method = RequestMethod.GET)
    @ResponseBody
    public List<ModuleConfig> getConfig() throws IOException {
        List<ModuleConfig> configuration = new ArrayList<>();

        for (Bundle bundle : bundleContext.getBundles()) {
            ModuleRegistrationData data = uiFrameworkService.getModuleDataByBundle(bundle);

            if (null != data) {
                Map<String, String> scripts = findScripts(bundle);

                for (Map.Entry<String, String> script : scripts.entrySet()) {
                    addConfig(configuration, data, script.getKey(), script.getValue());
                }

                List<String> angularModules = data.getAngularModules();
                String name = isEmpty(angularModules) ? null : angularModules.get(0);

                addConfig(configuration, data, name, "/js/app.js", data.getUrl());
            }
        }

        return configuration;
    }

    public UserInfo getUser(HttpServletRequest request) {
        String lang = localeService.getUserLocale(request).getLanguage();
        boolean securityLaunch = request.getUserPrincipal() != null;
        String userName = securityLaunch ? request.getUserPrincipal().getName() : "Admin Mode";

        return new UserInfo(userName, securityLaunch, lang);
    }

    private Map<String, String> findScripts(Bundle bundle) {
        List<URL> entries = getEntries(bundle);
        Map<String, String> scripts = new HashMap<>();
        List<String> requires = new ArrayList<>();

        for (URL entry : entries) {
            String content = getContent(entry);
            String path = getPath(entry);
            String filename = FilenameUtils.getBaseName(path);

            Matcher matcher = PATTERN.matcher(content);

            while (matcher.find()) {
                if (filename.equalsIgnoreCase("app")) {
                    String[] dependencies = getDependencies(matcher);
                    Collections.addAll(requires, dependencies);
                }

                String name = matcher.group(NAME_GROUP);

                if (!scripts.containsKey(name)) {
                    scripts.put(name, path);
                }
            }
        }

        Iterator<String> keyIterator = scripts.keySet().iterator();
        while (keyIterator.hasNext()) {
            String key = keyIterator.next();

            if (!requires.contains(key)) {
                keyIterator.remove();
            }
        }

        return scripts;
    }

    private void addConfig(List<ModuleConfig> configuration, ModuleRegistrationData data,
                           String name, String script) {
        addConfig(configuration, data, name, script, null);
    }

    private void addConfig(List<ModuleConfig> configuration, ModuleRegistrationData data,
                           String name, String script, String template) {
        ModuleConfig config = new ModuleConfig();
        config.setName(name);
        config.setScript("../" + data.getResourcePath() + script);
        config.setTemplate(template);

        if (isNotBlank(name)) {
            if (containsConfig(configuration, name)) {
                throw new IllegalStateException("The angular module name have to be unique");
            }

            configuration.add(config);
        }
    }

    private String getContent(URL url) {
        StringWriter writer = new StringWriter();
        String content;
        try {
            IOUtils.copy(url.openStream(), writer);
            content = writer.toString();
        } catch (IOException e) {
            LOGGER.error("There were problems with read entry: {}", url.getPath(), e);
            content = EMPTY;
        }

        return content.replace("\n", "");
    }

    private String getPath(URL url) {
        String path = url.getPath();
        int idx = path.indexOf("/js/");

        if (idx > 0) {
            path = path.substring(idx);
        }

        return path;
    }

    private List<URL> getEntries(Bundle bundle) {
        Enumeration enumeration = bundle.findEntries("/webapp/js", "*.js", true);
        return CastUtils.cast(URL.class, enumeration);
    }

    private String[] getDependencies(Matcher matcher) {
        String requires = matcher.group(DEPENDENCIES_GROUP);
        requires = requires.replaceAll("('|\"|\\s+)", "");

        return requires.split(",");
    }

    private boolean containsConfig(List<ModuleConfig> configuration, final String name) {
        return CollectionUtils.exists(configuration, new Predicate() {
            @Override
            public boolean evaluate(Object object) {
                boolean match = object instanceof ModuleConfig;

                if (match) {
                    ModuleConfig config = (ModuleConfig) object;
                    match = equalsIgnoreCase(name, config.getName());
                }

                return match;
            }
        });
    }

    @Autowired
    public void setUiFrameworkService(UIFrameworkService uiFrameworkService) {
        this.uiFrameworkService = uiFrameworkService;
    }

    @Autowired
    public void setBundleContext(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    @Autowired
    public void setLocaleService(LocaleService localeService) {
        this.localeService = localeService;
    }

    @Autowired
    public void setMenuBuilder(MenuBuilder menuBuilder) {
        this.menuBuilder = menuBuilder;
    }
}