package com.finance.query.controller;

import com.finance.query.service.SplitService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = QuerySplitController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
class QueryGroupMembersBatchControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SplitService service;

    @Test void acceptsCommaSeparatedIdsAndUsesTrustedCaller() throws Exception {
        UUID user = UUID.randomUUID();
        when(service.getGroupMembersBatch(user, List.of(1L, 2L))).thenReturn(Map.of(1L, List.of()));
        mvc.perform(get("/upsert/groups/members").header("X-User-Id", user)
                .param("groupIds", "1,2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.1").isArray());
        verify(service).getGroupMembersBatch(user, List.of(1L, 2L));
    }

    @Test void rejectsMissingIdentityOrIds() throws Exception {
        mvc.perform(get("/upsert/groups/members").param("groupIds", "1"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/upsert/groups/members").header("X-User-Id", UUID.randomUUID()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
