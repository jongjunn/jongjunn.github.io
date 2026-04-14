package com.wanted.ailienlmsprogram.global.config;

import com.wanted.ailienlmsprogram.global.security.CustomUserDetailsService;
import com.wanted.ailienlmsprogram.global.security.SecurityAccessDeniedHandler;
import com.wanted.ailienlmsprogram.global.security.SecurityAuthenticationEntryPoint;
import com.wanted.ailienlmsprogram.global.security.SecurityAuthenticationFailureHandler;
import com.wanted.ailienlmsprogram.global.security.SecurityLoginSuccessHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;


@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomUserDetailsService customUserDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final SecurityLoginSuccessHandler securityLoginSuccessHandler;
    private final SecurityAuthenticationFailureHandler securityAuthenticationFailureHandler;
    private final SecurityAuthenticationEntryPoint securityAuthenticationEntryPoint;
    private final SecurityAccessDeniedHandler securityAccessDeniedHandler;

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(customUserDetailsService);
        provider.setPasswordEncoder(passwordEncoder);

        // 권장: 아이디 존재 여부는 감추고, 아이디/비밀번호 오류를 하나로 처리
        provider.setHideUserNotFoundExceptions(true);

        return provider;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .authenticationProvider(authenticationProvider())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/login", "/signup", "/access-denied", "/continents", "/continents/*").permitAll()
                        .requestMatchers("/continents/*/posts", "/courses/*").authenticated()
                        .requestMatchers("/student/**").hasRole("STUDENT")
                        .requestMatchers("/instructor/**").hasRole("INSTRUCTOR")
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(securityAuthenticationEntryPoint)
                        .accessDeniedHandler(securityAccessDeniedHandler)
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .usernameParameter("loginId")
                        .passwordParameter("password")
                        .successHandler(securityLoginSuccessHandler)
                        .failureHandler(securityAuthenticationFailureHandler)
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutRequestMatcher(
                                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/logout")
                        )
                        .logoutSuccessUrl("/")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll()
                )
                .sessionManagement(session -> session
                        .invalidSessionUrl("/login?expired=true")
                        .sessionFixation(fixation -> fixation.changeSessionId())
                );

        return http.build();
    }
}