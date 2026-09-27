package com.sonrise.alerting.config;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Everything (admin page, admin API, H2 console) requires the single admin user, over HTTP basic
 * auth. The user comes from {@code spring.security.user.*}: set SPRING_SECURITY_USER_PASSWORD, or
 * Spring Boot generates a random password and logs it at startup. Nothing is hard-coded.
 *
 * <p>Two chains, because the H2 console needs looser rules than the rest:
 * <ul>
 *   <li>H2 console: no CSRF token (it posts its own forms), frames from its own origin, no CSP
 *       (it uses inline scripts).</li>
 *   <li>Everything else: CSRF on, and a strict Content-Security-Policy.</li>
 * </ul>
 *
 * <p>CSRF protection stays on even with basic auth: browsers cache basic-auth credentials and send
 * them automatically, also on requests another site triggers. {@code csrf().spa()} issues an
 * XSRF-TOKEN cookie that the admin page sends back as the X-XSRF-TOKEN header.
 */
@Configuration
public class SecurityConfig {

    /**
     * Only same-origin scripts, styles, images and requests; no inline script, no framing. A second
     * line of defence behind the page's own escaping of feed data.
     */
    static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";

    @Bean
    @Order(1)
    SecurityFilterChain h2ConsoleSecurity(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/h2-console/**")
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain appSecurity(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(requests -> requests
                        // A rejected request (e.g. 403 missing CSRF token) is forwarded to /error; that
                        // ERROR dispatch must not be re-checked, or every rejection turns into a
                        // misleading 401 login prompt. It only renders the error that already happened.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.spa())
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY)));
        return http.build();
    }
}
