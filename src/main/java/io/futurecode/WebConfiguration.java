package io.futurecode;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebConfiguration {
    @Bean public SecurityFilterChain security(HttpSecurity http) throws Exception {
        // Application sessions and their bound CSRF tokens are verified at each protected API entry.
        return http.csrf(AbstractHttpConfigurer::disable).cors(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(requests -> requests.anyRequest().permitAll()).build();
    }
}
