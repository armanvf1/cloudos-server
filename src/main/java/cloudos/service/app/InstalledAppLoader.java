package cloudos.service.app;

import cloudos.appstore.model.AppRuntime;
import cloudos.appstore.model.AppRuntimeDetails;
import cloudos.appstore.model.CloudOsAccount;
import cloudos.appstore.model.ConfigurableAppRuntime;
import cloudos.appstore.model.app.AppAuthConfig;
import cloudos.dao.AppDAO;
import cloudos.server.CloudOsConfiguration;
import cloudos.service.RootyService;
import com.sun.jersey.api.core.HttpContext;
import lombok.extern.slf4j.Slf4j;
import org.cobbzilla.util.http.CookieJar;
import org.cobbzilla.util.http.HttpMethods;
import org.cobbzilla.util.http.HttpRequestBean;
import org.cobbzilla.util.http.URIUtil;
import org.cobbzilla.util.json.JsonUtil;
import org.cobbzilla.wizard.cache.redis.RedisService;
import org.cobbzilla.wizard.util.BufferedResponse;
import org.cobbzilla.wizard.util.ProxyUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import rooty.toots.app.AppScriptMessage;
import rooty.toots.app.AppScriptMessageType;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.cobbzilla.util.json.JsonUtil.fromJson;
import static org.cobbzilla.util.json.JsonUtil.toJson;

@Service @Slf4j
public class InstalledAppLoader {

    public static final String APP_EMAIL = "email";
    public static final String APP_CALENDAR = "calendar";
    public static final String APP_FILES = "files";

    public static final AppRuntimeDetails ROUNDCUBE_DETAILS = new AppRuntimeDetails(APP_EMAIL, "/roundcube/", null);
    public static final AppRuntimeDetails ROUNDCUBE_CAL_DETAILS = new AppRuntimeDetails(APP_CALENDAR, "/roundcube/?_task=calendar", null);
    public static final AppRuntimeDetails OWNCLOUD_DETAILS = new AppRuntimeDetails(APP_FILES, "/owncloud/", null);

    public static final AppAuthConfig ROUNDCUBE_APP_AUTH = JsonUtil.fromJsonOrDie("{\n" +
            "        \"login_fields\": {\n" +
            "            \"_user\": \"{{account.name}}\",\n" +
            "            \"_pass\": \"{{account.password}}\",\n" +
            "            \"_timezone\": \"{{timezone-name}}\",\n" +
            "            \"_task\": \"login\",\n" +
            "            \"_action\": \"login\",\n" +
            "            \"_url\": \"_task=login\",\n" +
            "            \"_token\": \"pass\"\n" +
            "        },\n" +
            "        \"login_path\": \"./?_task=login\",\n" +
            "        \"login_page_markers\": [\n" +
            "            \"rcmloginuser\",\n" +
            "            \"rcmloginpwd\",\n" +
            "            \"_token\",\n" +
            "            \"rcmloginsubmit\"\n" +
            "        ]" +
            "    }", AppAuthConfig.class);

    public static final ConfigurableAppRuntime ROUNDCUBE = (ConfigurableAppRuntime) new ConfigurableAppRuntime()
            .setDetails(ROUNDCUBE_DETAILS)
            .setAuthentication(ROUNDCUBE_APP_AUTH);

    public static final ConfigurableAppRuntime ROUNDCUBE_CAL = (ConfigurableAppRuntime) new ConfigurableAppRuntime()
            .setDetails(ROUNDCUBE_CAL_DETAILS)
            .setAuthentication(ROUNDCUBE_APP_AUTH);

    public static final ConfigurableAppRuntime OWNCLOUD = (ConfigurableAppRuntime) new ConfigurableAppRuntime()
            .setDetails(OWNCLOUD_DETAILS)
            .setAuthentication(JsonUtil.fromJsonOrDie("{\n" +
                    "        \"login_fields\": {\n" +
                    "            \"user\": \"{{account.name}}\",\n" +
                    "            \"password\": \"{{account.password}}\",\n" +
                    "            \"remember_login\": \"1\",\n" +
                    "            \"timezone-offset\": \"{{timezone-offset}}\",\n" +
                    "            \"requesttoken\": \"pass\"\n" +
                    "        },\n" +
                    "        \"home_path\": \"index.php\",\n" +
                    "        \"login_path\": \"index.php\",\n" +
                    "        \"login_page_markers\": [ \"<form method=\\\"post\\\" name=\\\"login\\\">\", \"class=\\\"login primary\\\"\" ]\n" +
                    "    }", AppAuthConfig.class));

    public static final Map<String, AppRuntime> APPS_BY_NAME = initAppMap();

    private static final int MAX_REDIRECTS = 10;

    private static Map<String, AppRuntime> initAppMap() {
        final Map<String, AppRuntime> appMap = new LinkedHashMap<>();
        appMap.put(APP_EMAIL, ROUNDCUBE);
        appMap.put(APP_CALENDAR, ROUNDCUBE_CAL);
        appMap.put(APP_FILES, OWNCLOUD);
        return appMap;
    }

    @Autowired private CloudOsConfiguration configuration;
    @Autowired private AppDAO appDAO;
    @Autowired private RootyService rooty;
    @Autowired private RedisService redis;

    public static final Map<String, AppRuntimeDetails> APP_DETAILS_BY_NAME = initAppDetailsMap();

    private static Map<String, AppRuntimeDetails> initAppDetailsMap() {
        final Map<String, AppRuntimeDetails> appDetailsMap = new LinkedHashMap<>();
        appDetailsMap.put(APP_EMAIL, ROUNDCUBE_DETAILS);
        appDetailsMap.put(APP_CALENDAR, ROUNDCUBE_CAL_DETAILS);
        appDetailsMap.put(APP_FILES, OWNCLOUD_DETAILS);
        return appDetailsMap;
    }

    public Response loadApp(String apiKey, CloudOsAccount account, AppRuntime app, HttpContext context) throws IOException {

        if (app.hasUserManagement()) {
            try {
                if (!userExists(account, app) && !createUser(account, app)) {
                    log.error("error registering user " + account.getName() + " with app " + app.getDetails().getName());
                }
            } catch (Exception e) {
                log.warn("Error registering user "+account.getName()+" with app "+app.getDetails().getName());
            }
        }

        final AppProxyContext pctx = new AppProxyContext()
                .setApiKey(apiKey)
                .setConfiguration(configuration)
                .setAccount(account)
                .setApp(app);
        final String appPath = pctx.getAppPath();
        final String appHome = pctx.getAppHome();
        final HttpRequestBean<String> requestBean = new HttpRequestBean<>(HttpMethods.GET, appPath);

        // Supports HTTP auth... store authHeaderValue in AuthTransition and send directly to app
        // Proxy will use authHeaderValue to populate Authorization header
        if (app.getAuthentication().hasHttp_auth()) return sendToApp(pctx);

        BufferedResponse response;
        CookieJar cookieJar;
        AuthTransition authTransition = null;

        // If already logged in once, verify the auth works and then send them on their way
        log.info("loadApp: looking for pre-existing AuthTransition...");
        try {
            final String json = redis.get(pctx.getAuthKey());
            if (json != null) authTransition = fromJson(json, AuthTransition.class);
        } catch (Exception e) {
            log.error("Error looking up authTransition in redis: "+e, e);
        }
        if (authTransition != null) {
            log.info("loadApp: found pre-existing AuthTransition, verifying...");
            cookieJar = new CookieJar(authTransition.getCookies());
            response = ProxyUtil.proxyResponse(requestBean, context, appHome, cookieJar);
            response = followRedirects(context, appPath, response, cookieJar);

            log.info("loadApp: AuthTransition verification returned response "+response.getStatus()+", ensuring this is not a login page");
            if (response.isSuccess() && !app.isLoginPage(response.getDocument())) {
                // success, and user appears to be logged in: redirect to app page
                log.info("loadApp: pre-existing AuthTransition is OK, sending to app...");
                pctx.setCookieJar(cookieJar);
                return sendToApp(pctx);
            } else {
                // this thing doesn't work, nuke it to save space
                redis.del(authTransition.getUuid());
                redis.del(pctx.getAuthKey());
            }
        }

        // Not logged in, start with a fresh request and fresh cookie jar
        log.info("loadApp: pre-existing AuthTransition NOT found, starting fresh request...");
        cookieJar = pctx.getCookieJar();

        // request the app home page, will load or will redirect us to a login page
        // this step is often used to set cookies and other tokens
        response = ProxyUtil.proxyResponse(requestBean, context, appHome, cookieJar);
        response = followRedirects(context, appPath, response, cookieJar);
        if (!response.isSuccess() || !app.isLoginPage(response.getDocument())) {
            // error, or user appears to be logged in, simply redirect to app page
            return sendToApp(pctx);
        }

        // attempt login and see what the app sends back
        log.info("loadApp: attempting login for " + appPath + " account " + account.getName());
        final HttpRequestBean<String> authRequest = app.buildLoginRequest(account, response, context, appPath);
        response = ProxyUtil.proxyResponse(authRequest, context, appPath, cookieJar);
        if (!response.isSuccess() || app.isLoginPage(response.getDocument())) {
            log.warn("loadApp: login failed, sending to main app page");
            return sendToApp(pctx);
        }

        String location;
        AppAuthConfig appAuth = pctx.getAppAuth();
        if (appAuth != null) {
            // follow a redirect if the Location matches the registration_redirect or login_redirect regex (if either are set)
            if (appAuth.hasRegistration_redirect() && response.is3xx()) {
                location = response.getFirstHeaderValue(HttpHeaders.LOCATION);
                if (appAuth.getRegistrationRedirectPattern().matcher(location).matches()) {

                    final BufferedResponse resolved = followRedirects(context, appPath, response, cookieJar);
                    if (resolved == null) {
                        log.warn("Too many redirects, sending to main app page");
                        return sendToApp(pctx);
                    }

                    // if this is a registration page, register ourselves...
                    if (resolved.isSuccess() && app.isRegistrationPage(resolved.getDocument())) {
                        final HttpRequestBean<String> registrationRequest = app.buildRegistrationRequest(account, resolved, context, appPath);
                        response = ProxyUtil.proxyResponse(registrationRequest, context, appPath, cookieJar);

                        if (!response.isSuccess() || app.isRegistrationPage(response.getDocument())) {
                            log.warn("registration failed, sending to main app page");
                        }
                        return sendToApp(pctx);
                    }

                } else if (appAuth.getLoginRedirectPattern().matcher(location).matches()) {

                    final BufferedResponse resolved = followRedirects(context, appPath, response, cookieJar);
                    if (resolved == null) {
                        log.warn("Too many redirects, sending to main app page");
                        return sendToApp(pctx);
                    }

                    if (resolved.isSuccess() && !app.isLoginPage(resolved.getDocument())) {
                        pctx.setLocation(resolved.getRequestUri());
                        return sendToApp(pctx);
                    }
                } else {
                    // relay the redirect to the end user
                    pctx.setLocation(location);
                    return sendToApp(pctx);
                }
            }
        }

        return sendToApp(pctx);
    }

    private boolean userExists(CloudOsAccount account, AppRuntime app) {
        final AppScriptMessage message = new AppScriptMessage()
                .setApp(app.getDetails().getName())
                .setType(AppScriptMessageType.user_exists)
                .addArg(account.getName());
        return Boolean.valueOf(rooty.request(message).getResults());
    }

    private boolean createUser(CloudOsAccount account, AppRuntime app) {
        final AppScriptMessage message = new AppScriptMessage()
                .setApp(app.getDetails().getName())
                .setType(AppScriptMessageType.user_create)
                .addArg(account.getName())
                .addArg(account.getPassword())
                .addArg(String.valueOf(account.isAdmin()));
        return Boolean.valueOf(rooty.request(message).getResults());
    }

    public Response sendToApp(AppProxyContext pctx) {

        String appPath = pctx.getAppPath();
        String location = pctx.getLocation();
        final CookieJar cookieJar = pctx.getCookieJar();

        if (!appPath.endsWith("/")) appPath += "/";
        if (!(location.startsWith("http://") || location.startsWith("https://"))) location = appPath + location;

        final AuthTransition auth = new AuthTransition(location);
        if (pctx.hasHttpAuth()) {
            // If the app proxies via Apache, mod_session_header will use this cookie to populate the Authorization header
            auth.setAuthHeaderValue(pctx.getAuthHeaderValue());
            cookieJar.add(auth.getAuthCookie());

            // If the app proxies via CloudOs, the AppProxy will identify the user from this session
            auth.setSessionId(pctx.getApiKey());
            cookieJar.add(auth.getSessionCookie());
        }
        auth.setCookies(cookieJar.getCookiesList());

        final String uuid = auth.getUuid();
        try {
            final String authJson = toJson(auth);
            final String authKey = pctx.getAuthKey();

            redis.del(uuid);
            redis.set(uuid, authJson, "NX", "EX", TimeUnit.HOURS.toSeconds(24));
            redis.del(authKey);
            redis.set(authKey, authJson, "NX", "EX", TimeUnit.HOURS.toSeconds(24));

        } catch (Exception e) {
            log.error("sendToApp: Error writing to redis: "+e, e);
            return Response.serverError().build();
        }

        return sendToApp(appPath, uuid);
    }

    protected Response sendToApp(String appPath, String authTransitionUuid) {
        final String hostname = configuration.getHostname();
        String path = URIUtil.getHost(appPath).equals(hostname)
                ? URIUtil.toUri(configuration.getPublicUriBase()).getScheme()+"://"+hostname
                : appPath;
        if (!path.endsWith("/")) path += "/";

        Response.ResponseBuilder responseBuilder = Response.temporaryRedirect(URIUtil.toUri(path + "__cloudos__/api/app/auth/"+authTransitionUuid));
        return responseBuilder.build();
    }

    public BufferedResponse followRedirects(HttpContext context, String appPath,
                                            BufferedResponse response, CookieJar cookieJar) throws IOException {
        // shouldn't happen, but just in case
        if (response == null) return null;

        // follow up to MAX_REDIRECTs trying to get to a 2xx response. track cookies along the way.
        int redirCount = 0;
        String location;
        while ((location = response.getRedirectUri()) != null) {
            final HttpRequestBean<String> redirect = new HttpRequestBean<>(appPath + location);
            response = ProxyUtil.proxyResponse(redirect, context, appPath, cookieJar);
            if (++redirCount > MAX_REDIRECTS) response = null;
        }
        return response;
    }

}