package com.finance.query.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.query.dto.GroupBalanceResponse;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard: Spring Data's PageImpl cannot be deserialized by Jackson
 * out of the box, which made every cached page (transactions, group-expenses)
 * throw "Cannot construct instance of PageImpl" on read and fall back to the DB.
 * See RedisConfig.PageImplDeserializer.
 */
class RedisConfigPageCacheTest {

    private final ObjectMapper mapper = new RedisConfig().redisObjectMapper();

    @Test
    void pageRoundTripsThroughRedisSerializer() throws Exception {
        Page<Object> page = new PageImpl<>(List.of("a", "b"), PageRequest.of(2, 8), 26);

        Object restored = mapper.readValue(mapper.writeValueAsString(page), Object.class);

        assertThat(restored).isInstanceOf(PageImpl.class);
        PageImpl<?> result = (PageImpl<?>) restored;
        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0)).isEqualTo("a");
        assertThat(result.getContent().get(1)).isEqualTo("b");
        assertThat(result.getNumber()).isEqualTo(2);
        assertThat(result.getSize()).isEqualTo(8);
        assertThat(result.getTotalElements()).isEqualTo(26);
    }

    @Test
    void pageContentKeepsConcreteTypes() throws Exception {
        var dto = new GroupBalanceResponse(6L, "Twilio-Gang", List.of(), List.of());
        Page<Object> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 8), 1);

        Object restored = mapper.readValue(mapper.writeValueAsString(page), Object.class);

        PageImpl<?> result = (PageImpl<?>) restored;
        assertThat(result.getContent()).singleElement()
                .isInstanceOf(GroupBalanceResponse.class)
                .satisfies(o -> assertThat(((GroupBalanceResponse) o).getGroupName()).isEqualTo("Twilio-Gang"));
    }
}
