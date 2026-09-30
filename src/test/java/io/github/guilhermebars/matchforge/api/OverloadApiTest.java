package io.github.guilhermebars.matchforge.api;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.guilhermebars.matchforge.service.EngineService;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ExchangeController.class)
@Import(ApiMapper.class)
class OverloadApiTest {
    @Autowired
    MockMvc mvc;

    @MockitoBean
    EngineService engine;

    @Test
    void unwrapsWriterOverloadAs503() throws Exception {
        when(engine.submit(any()))
                .thenReturn(CompletableFuture.failedFuture(new EngineService.UnavailableException("full")));
        mvc.perform(post("/api/v1/accounts").contentType("application/json").content("{\"id\":\"busy\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ENGINE_OVERLOADED"));
    }
}
