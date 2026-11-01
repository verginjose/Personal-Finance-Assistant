package com.finance.query.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CacheConsistencyGuard — decides whether a cache fill is safe")
class CacheConsistencyGuardTest {

    private static final String SCOPE = "user:11111111-1111-1111-1111-111111111111";

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> ops;

    @Mock
    private JdbcTemplate jdbc;

    @InjectMocks
    private CacheConsistencyGuard guard;

    @Test
    @DisplayName("caches when no write raced and the replica has replayed")
    void cachesWhenConsistent() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.multiGet(any())).thenReturn(List.of("7", "0/95BEDD0"));
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any())).thenReturn(true);

        CacheConsistencyGuard.Snapshot before = guard.snapshot(SCOPE);

        assertThat(guard.canCache(SCOPE, before)).isTrue();
        verify(jdbc).queryForObject(anyString(), eq(Boolean.class), eq("0/95BEDD0"));
    }

    @Test
    @DisplayName("refuses to cache when a write bumped the generation during the load")
    void refusesWhenWriteRaced() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.multiGet(any()))
                .thenReturn(List.of("7", "0/95BEDD0"))   // before the load
                .thenReturn(List.of("8", "0/9A00000"));  // after it

        CacheConsistencyGuard.Snapshot before = guard.snapshot(SCOPE);

        assertThat(guard.canCache(SCOPE, before)).isFalse();
        verify(jdbc, never()).queryForObject(anyString(), eq(Boolean.class), any());
    }

    @Test
    @DisplayName("refuses to cache while the replica has not replayed the write")
    void refusesWhenReplicaBehind() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.multiGet(any())).thenReturn(List.of("7", "0/95BEDD0"));
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any())).thenReturn(false);

        CacheConsistencyGuard.Snapshot before = guard.snapshot(SCOPE);

        assertThat(guard.canCache(SCOPE, before)).isFalse();
    }

    @Test
    @DisplayName("caches when the scope has no write tokens yet")
    void cachesWhenNoTokens() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.multiGet(any())).thenReturn(Arrays.asList(null, null));

        CacheConsistencyGuard.Snapshot before = guard.snapshot(SCOPE);

        assertThat(guard.canCache(SCOPE, before)).isTrue();
        verify(jdbc, never()).queryForObject(anyString(), eq(Boolean.class), any());
    }

    @Test
    @DisplayName("fails closed when the tokens cannot be read")
    void failsClosedWhenRedisDown() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.multiGet(any())).thenThrow(new IllegalStateException("redis down"));

        CacheConsistencyGuard.Snapshot before = guard.snapshot(SCOPE);

        assertThat(before).isNull();
        assertThat(guard.canCache(SCOPE, before)).isFalse();
    }

    @Test
    @DisplayName("re-reads the tokens after the load, not just once")
    void readsTokensBeforeAndAfter() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.multiGet(any())).thenReturn(Arrays.asList("1", null));

        CacheConsistencyGuard.Snapshot before = guard.snapshot(SCOPE);
        guard.canCache(SCOPE, before);

        verify(ops, times(2)).multiGet(any());
    }
}
