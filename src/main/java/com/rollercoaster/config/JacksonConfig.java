package com.rollercoaster.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Configures Spring Boot 4's Jackson 3 {@link JsonMapper} so partial JSON payloads
 * targeting Java records can omit primitive fields without failing deserialization.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public JsonMapperBuilderCustomizer jsonMapperBuilderCustomizer() {
        return JacksonConfig::configureBuilder;
    }

    public static JsonMapper.Builder configureBuilder(JsonMapper.Builder builder) {
        return builder.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

    public static JacksonJsonHttpMessageConverter createJsonHttpMessageConverter() {
        return new JacksonJsonHttpMessageConverter(configureBuilder(JsonMapper.builder()));
    }
}
