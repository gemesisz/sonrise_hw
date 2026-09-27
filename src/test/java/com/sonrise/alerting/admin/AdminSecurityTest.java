package com.sonrise.alerting.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.net.HttpCookie;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security over real HTTP (a real server on a random port, not MockMvc), so the H2 console
 * servlet and the CSRF cookie round trip behave exactly as in a browser.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "alerting.sources.usgs.enabled=false",
        "alerting.sources.rss.enabled=false",
        "alerting.sources.coingecko.enabled=false"
})
class AdminSecurityTest {

    @Value("${local.server.port}")
    private int port;

    private RestClient client() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                // Return every status instead of throwing, so tests can assert on it.
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes());
    }

    @Test
    void apiRequiresLogin() {
        assertThat(client().get().uri("/api/admin/users").retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(client().get().uri("/api/admin/users")
                .header(HttpHeaders.AUTHORIZATION, basic("admin", "wrong")).retrieve().toBodilessEntity()
                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(client().get().uri("/api/admin/users")
                .header(HttpHeaders.AUTHORIZATION, basic("admin", "test-password")).retrieve().toBodilessEntity()
                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void h2ConsoleRequiresLogin() {
        assertThat(client().get().uri("/h2-console/").retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(client().get().uri("/h2-console/")
                .header(HttpHeaders.AUTHORIZATION, basic("admin", "test-password")).retrieve().toBodilessEntity()
                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void writesNeedTheCsrfTokenFromTheCookie() {
        String auth = basic("admin", "test-password");
        String body = "{\"name\": \"Mallory's victim\"}";

        // Without the token (what a cross-site form would send): rejected even with valid credentials.
        assertThat(client().post().uri("/api/admin/users").header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // Like the admin page: a GET hands out the XSRF-TOKEN cookie, the write echoes it as a header.
        ResponseEntity<Void> page = client().get().uri("/api/admin/users").header(HttpHeaders.AUTHORIZATION, auth)
                .retrieve().toBodilessEntity();
        String token = xsrfToken(page.getHeaders().get(HttpHeaders.SET_COOKIE));
        assertThat(token).isNotBlank();

        assertThat(client().post().uri("/api/admin/users").header(HttpHeaders.AUTHORIZATION, auth)
                .header(HttpHeaders.COOKIE, "XSRF-TOKEN=" + token).header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    private static String xsrfToken(List<String> setCookies) {
        assertThat(setCookies).as("Set-Cookie headers").isNotNull();
        return setCookies.stream()
                .flatMap(header -> HttpCookie.parse(header).stream())
                .filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
