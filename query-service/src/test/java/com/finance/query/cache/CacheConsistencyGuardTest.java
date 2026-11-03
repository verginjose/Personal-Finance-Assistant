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
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any())).thenReturn(true);

        CacheConsistencyGuard.Snapshot before = guard.snapshot(SCOPE);

        assertThat(guard.canCache(SCOPE, before)).isFalse();
    }

    @Test
    @DisplayName("refuses to cache when the replica catches up during the load")
    void refusesWhenReplicaCatchesUpDuringLoad() {
        // The reported bug: at load start the replica had not replayed the
        // delete; by the time the load finished it had. Checking the replica
        // after the load certified the pre-delete row as fresh and cached it
        // for the full TTL. The pre-load answer must be the one that counts.
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.multiGet(any())).thenReturn(List.of("7", "0/95BEDD0"));
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any()))
                .thenReturn(false, true);   // behind at snapshot, caught up later

        CacheConsistencyGuard.Snapshot before = guard.snapshot(SCOPE);

        assertThat(guard.canCache(SCOPE, before)).isFalse();
        // Exactly one replica check — taken before the load, not after it.
        verify(jdbc, times(1)).queryForObject(anyString(), eq(Boolean.class), any());
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
    @DisplayName("refuses to cache when the scope has no write tokens")
    void refusesWhenNoTokens() {
        // Missing tokens cannot be distinguished from lost tokens, and a value
        // that cannot be proven fresh must not be stored for the whole TTL.
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.multiGet(any())).thenReturn(Arrays.asList(null, null));

        CacheConsistencyGuard.Snapshot before = guard.snapshot(SCOPE);

        assertThat(before).isNull();
        assertThat(guard.canCache(SCOPE, before)).isFalse();
        verify(jdbc, never()).queryForObject(anyString(), eq(Boolean.class), any());
    }

    @Test
    @DisplayName("refuses to cache when the WAL token is missing but the generation exists")
    void refusesWhenWalTokenMissing() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.multiGet(any())).thenReturn(Arrays.asList("7", null));

        CacheConsistencyGuard.Snapshot before = guard.snapshot(SCOPE);

        assertThat(before).isNull();
        assertThat(guard.canCache(SCOPE, before)).isFalse();
        verify(jdbc, never()).queryForObject(anyString(), eq(Boolean.class), any());
    }

    @Test
    @DisplayName("waits for the replica to replay the write before the load")
    void awaitsReplicaUntilCaughtUp() {
        // The fill right after a write races the ~10 ms replay window.
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any()))
                .thenReturn(false, false, true);

        boolean caughtUp = guard.awaitReplica("0/95BEDD0", 250);

        assertThat(caughtUp).isTrue();
        verify(jdbc, times(3)).queryForObject(anyString(), eq(Boolean.class), eq("0/95BEDD0"));
    }

    @Test
    @DisplayName("gives up waiting when the replica stays behind")
    void awaitReplicaTimesOut() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any())).thenReturn(false);

        long started = System.nanoTime();
        boolean caughtUp = guard.awaitReplica("0/95BEDD0", 20);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(caughtUp).isFalse();
        assertThat(elapsedMs).isBetween(15L, 500L);
    }

    @Test
    @DisplayName("does not wait when there is no WAL token")
    void awaitReplicaIgnoresBlankToken() {
        assertThat(guard.awaitReplica("", 250)).isFalse();
        assertThat(guard.awaitReplica(null, 250)).isFalse();
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
        when(ops.multiGet(any())).thenReturn(List.of("1", "0/95BEDD0"));
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any())).thenReturn(true);

        CacheConsistencyGuard.Snapshot before = guard.snapshot(SCOPE);
        guard.canCache(SCOPE, before);

        verify(ops, times(2)).multiGet(any());
    }
}
