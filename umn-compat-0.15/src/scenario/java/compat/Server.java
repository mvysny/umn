package compat;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The proxy server's admin REST API as the 0.12 image serves it, over plain HTTP so no admin client version is
 * involved.
 *
 * <pre>{@code
 * Server.createUser("http://localhost:32768", user, pass);   // as Aedict-Online's registration does
 * long records = Server.logSize("http://localhost:32768", user);
 * }</pre>
 */
public final class Server {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    public static void createUser(String adminUrl, String user, String pass) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(adminUrl + "/admin/users?username="
                        + encode(user) + "&password=" + encode(pass)))
                .PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 204) {
            throw new IOException("creating " + user + ": " + response.statusCode() + " " + response.body());
        }
    }

    public static void deleteUser(String adminUrl, String user) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(adminUrl + "/admin/users/" + encode(user)))
                .DELETE().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 204) {
            throw new IOException("deleting " + user + ": " + response.statusCode() + " " + response.body());
        }
    }

    /**
     * @return the JSON array of user names
     */
    public static String users(String adminUrl) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create(adminUrl + "/admin/users")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    /**
     * @return the number of records in {@code user}'s global log
     */
    public static long logSize(String adminUrl, String user) throws IOException, InterruptedException {
        String json = HTTP.send(HttpRequest.newBuilder(URI.create(adminUrl + "/admin/stats/logsizes")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        Matcher m = Pattern.compile("\"" + Pattern.quote(user) + "\"\\s*:\\s*(\\d+)").matcher(json);
        if (!m.find()) {
            throw new IOException("no log size for " + user + " in " + json);
        }
        return Long.parseLong(m.group(1));
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private Server() {
    }
}
