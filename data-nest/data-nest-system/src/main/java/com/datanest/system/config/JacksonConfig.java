package com.datanest.system.config;

import org.springframework.boot.jackson.JacksonComponent;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

@JacksonComponent
public class JacksonConfig {

    @JacksonComponent
    public static class LongToStringSerializer extends ValueSerializer<Long> {

        public LongToStringSerializer() {
            super();
        }

        @Override
        public void serialize(Long value, JsonGenerator gen, SerializationContext ctxt)
                throws JacksonException {
            if (value == null) {
                gen.writeNull();
            } else {
                gen.writeString(value.toString());
            }
        }
    }
}
