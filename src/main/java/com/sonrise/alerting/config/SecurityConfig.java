package com.sonrise.alerting.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Everything (admin API, admin page, H2 console) requires the single admin user, over HTTP basic
 * auth. The user comes from {@code spring.security.user.*}: set SPRING_SECURITY_USER_PASSWORD, or
 * Spring Boot generates a random password and logs it at startup. Nothing is hard-coded.
 *
 * <p>CSRF protection stays on: browsers cache basic-auth credentials and send them automatically,
 * also on requests another site triggers. {@code csrf().spa()} issues an XSRF-TOKEN cookie that the
 * admin page sends back as the X-XSRF-TOKEN header.
 */
@Configuration
public class SecurityConfig {

    private static final String H2_CONSOLE = "/h2-console/**";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // The H2 console posts its own forms without our token; it is still behind the login.
                .csrf(csrf -> csrf.spa().ignoringRequestMatchers(H2_CONSOLE))
                // The H2 console uses frames from its own origin.
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        return http.build();
    }
}
