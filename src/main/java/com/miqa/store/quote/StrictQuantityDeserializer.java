package com.miqa.store.quote;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Do not silently coerce decimals, numeric strings or booleans into a quantity. */
public class StrictQuantityDeserializer extends ValueDeserializer<Long> {
    @Override public Long deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) throw QuoteRequestFailure.invalid();
        return parser.getLongValue();
    }
}
