package com.dogancaglar.paymentservice.config

import com.dogancaglar.common.time.Utc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.web.SecurityFilterChain

/**
 * payment-consumers serves one small read API next to its Kafka listeners: balances of sellers and
 * merchants (BalanceController). Callers present a Keycloak JWT; the same rules as payment-service apply
 * (realm roles become authorities, see keycloakJwtAuthenticationConverter).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests { requests ->
                requests
                    // health, liveness, readiness, metrics
                    .requestMatchers("/actuator/**").permitAll()
                    // own balance: seller user (SELLER), seller-api client (SELLER_API) or merchant client (MERCHANT)
                    .requestMatchers(HttpMethod.GET, "/api/v1/balances/me").hasAnyRole("SELLER", "SELLER_API", "MERCHANT")
                    // one seller's balance: a merchant for its own sellers, the back office for any seller
                    .requestMatchers(HttpMethod.GET, "/api/v1/balances/*").hasAnyRole("MERCHANT", "FINANCE", "ADMIN")
                    // everything else stays closed
                    .anyRequest().denyAll()
            }
            .exceptionHandling { exceptions ->
                exceptions
                    // no or invalid JWT
                    .authenticationEntryPoint { request, response, _ ->
                        response.status = HttpStatus.UNAUTHORIZED.value()
                        response.contentType = "application/json"
                        response.writer.write(
                            """{"timestamp":"${Utc.nowInstant()}","status":401,"error":"Unauthorized","message":"Authentication required. Please provide a valid JWT token.","path":"${request.requestURI}"}"""
                        )
                    }
                    // valid JWT, but not allowed
                    .accessDeniedHandler { request, response, _ ->
                        response.status = HttpStatus.FORBIDDEN.value()
                        response.contentType = "application/json"
                        response.writer.write(
                            """{"timestamp":"${Utc.nowInstant()}","status":403,"error":"Forbidden","message":"Access denied. You do not have the required permissions.","path":"${request.requestURI}"}"""
                        )
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

    // Keycloak puts realm roles in realm_access.roles. Each role becomes an authority as-is (for
    // hasAuthority, e.g. "payment:write") and, for role-like names, also ROLE_<name> (for hasRole).
    private fun keycloakJwtAuthenticationConverter(): JwtAuthenticationConverter {
        val authoritiesConverter = Converter<Jwt, Collection<GrantedAuthority>> { jwt ->
            val realmAccess = jwt.claims["realm_access"] as? Map<*, *>
            val roles = realmAccess?.get("roles") as? List<*>

            val authorities = mutableListOf<GrantedAuthority>()
            if (roles != null) {
                for (role in roles) {
                    val roleName = role.toString()
                    authorities.add(SimpleGrantedAuthority(roleName))
                    if (!roleName.startsWith("ROLE_") && !roleName.contains(":")) {
                        authorities.add(SimpleGrantedAuthority("ROLE_$roleName"))
                    }
                }
            }
            authorities
        }

        val converter = JwtAuthenticationConverter()
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter)
        return converter
    }
}
