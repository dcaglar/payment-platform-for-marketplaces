package com.dogancaglar.paymentservice.adapter.inbound.rest.webconfig

import com.dogancaglar.common.time.Utc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.core.convert.converter.Converter
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.web.SecurityFilterChain

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@ConditionalOnWebApplication
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests { requests ->
                requests
                    // Allow actuator endpoints (health, liveness, readiness, prometheus) without authentication
                    .requestMatchers("/actuator/**").permitAll()
                    // internal system (checkout) creates payment intents
                    .requestMatchers(HttpMethod.POST, "/api/v1/payments").hasAuthority("payment:write")
                    // polling for payment intent status
                    .requestMatchers(HttpMethod.GET, "/api/v1/payments/*").hasAuthority("payment:write")
                    // internal system (checkout) authorizes payment intents
                    .requestMatchers(HttpMethod.POST, "/api/v1/payments/*/authorize").hasAuthority("payment:write")
                    // balances are served by payment-consumers (GET /api/v1/balances/...), not here
                    // default: deny everything else unless explicitly allowed
                    .anyRequest().denyAll()
            }
            .exceptionHandling { exceptions ->
                exceptions
                    // Return 401 Unauthorized when authentication fails (no valid JWT)
                    .authenticationEntryPoint { request, response, authException ->
                        response.status = HttpStatus.UNAUTHORIZED.value()
                        response.contentType = "application/json"
                        response.writer.write("""
                            {
                                "timestamp": "${Utc.nowInstant()}",
                                "status": 401,
                                "error": "Unauthorized",
                                "message": "Authentication required. Please provide a valid JWT token.",
                                "path": "${request.requestURI}"
                            }
                        """.trimIndent())
                    }
                    // Return 403 Forbidden when authorization fails (valid JWT but insufficient permissions)
                    .accessDeniedHandler { request, response, accessDeniedException ->
                        response.status = HttpStatus.FORBIDDEN.value()
                        response.contentType = "application/json"
                        response.writer.write("""
                            {
                                "timestamp": "${Utc.nowInstant()}",
                                "status": 403,
                                "error": "Forbidden",
                                "message": "Access denied. You do not have the required permissions.",
                                "path": "${request.requestURI}"
                            }
                        """.trimIndent())
                    }
            }
            .csrf { it.disable() }
            .cors { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .oauth2ResourceServer { oauth ->
                oauth.jwt { jwtConfigurer ->
                    jwtConfigurer.jwtAuthenticationConverter(keycloakJwtAuthenticationConverter())
                }
            }

        return http.build()
    }

    private fun keycloakJwtAuthenticationConverter(): JwtAuthenticationConverter {
        val converter = JwtAuthenticationConverter()

        // Custom converter that handles both roles (with ROLE_ prefix) and authorities (without prefix)
        val customConverter = Converter<Jwt, Collection<GrantedAuthority>> { jwt ->
            val roles = jwt.claims["realm_access"] as? Map<*, *>
            val roleList = roles?.get("roles") as? List<*>
            
            val authorities = mutableListOf<GrantedAuthority>()
            
            roleList?.forEach { role ->
                val roleName = role.toString()
                // Add authority without prefix (for hasAuthority checks like "payment:write")
                authorities.add(SimpleGrantedAuthority(roleName))
                // Add role with ROLE_ prefix (for hasRole checks like "SELLER", "FINANCE")
                // Only add ROLE_ prefix if it doesn't already have it and if it's a role-like name
                if (!roleName.startsWith("ROLE_") && !roleName.contains(":")) {
                    authorities.add(SimpleGrantedAuthority("ROLE_$roleName"))
                }
            }
            
            authorities
        }

        converter.setJwtGrantedAuthoritiesConverter(customConverter)
        return converter
    }
}