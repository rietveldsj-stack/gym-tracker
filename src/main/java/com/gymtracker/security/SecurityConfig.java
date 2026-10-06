package com.gymtracker.security;

import com.gymtracker.account.AppUserRepository;
import com.gymtracker.account.PasswordChangeSignOutFilter;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
public class SecurityConfig {

    private static final int ONE_YEAR_SECONDS = 365 * 24 * 3600;

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    static String requireRememberMeKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("REMEMBER_ME_KEY must be set");
        }
        return key;
    }

    @Bean
    PersistentTokenRepository persistentTokenRepository(DataSource dataSource) {
        JdbcTokenRepositoryImpl repository = new JdbcTokenRepositoryImpl();
        repository.setDataSource(dataSource);
        return repository;
    }

    /**
     * Database-backed tokens. The hash-based variant signs cookies with the stored password hash, which changes
     * on every start because the password is re-hashed with a new salt, so every deploy would sign the user out.
     */
    @Bean
    RememberMeServices rememberMeServices(@Value("${app.remember-me-key}") String key,
                                          @Value("${app.secure-cookies}") boolean secureCookies,
                                          UserDetailsService userDetailsService,
                                          PersistentTokenRepository tokenRepository) {
        var services = new PersistentTokenBasedRememberMeServices(
                requireRememberMeKey(key), userDetailsService, tokenRepository);
        services.setAlwaysRemember(true);
        services.setTokenValiditySeconds(ONE_YEAR_SECONDS);
        services.setUseSecureCookie(secureCookies);
        return services;
    }

    /**
     * The app's own files skip the security filters. Browsers load them in parallel; if each request ran a
     * remember-me login, the single-use tokens would race, trip the cookie-theft check and sign the user out.
     */
    @Bean
    WebSecurityCustomizer staticFilesBypassSecurity() {
        return web -> web.ignoring().requestMatchers("/", "/index.html", "/styles.css", "/manifest.json", "/sw.js",
                "/icons/**", "/js/**", "/vendor/**", "/test/**");
    }

    /** Where form login and registration save the signed-in user. */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            SecurityContextRepository securityContextRepository,
                                            Clock clock,
                                            AppUserRepository users,
                                            RememberMeServices rememberMeServices,
                                            @Value("${app.remember-me-key}") String key) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()).spa())
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .addFilterAfter(new PasswordChangeSignOutFilter(users, clock), RememberMeAuthenticationFilter.class)
                .formLogin(form -> form
                        .loginProcessingUrl("/login")
                        .successHandler((request, response, authentication) -> {
                            PasswordChangeSignOutFilter.markSignedIn(request, clock.instant());
                            response.setStatus(200);
                        })
                        .failureHandler((request, response, exception) -> {
                            response.setStatus(401);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"message\":\"Wrong email or password\"}");
                        }))
                .rememberMe(remember -> remember.rememberMeServices(rememberMeServices).key(key))
                .requestCache(cache -> cache.disable())
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        PathPatternRequestMatcher.withDefaults().matcher("/api/**")));
        return http.build();
    }
}
