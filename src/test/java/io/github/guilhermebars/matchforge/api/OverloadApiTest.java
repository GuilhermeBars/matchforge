package io.github.guilhermebars.matchforge.api;

import io.github.guilhermebars.matchforge.service.EngineService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.concurrent.CompletableFuture;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ExchangeController.class) @Import(ApiMapper.class)
class OverloadApiTest {
    @Autowired MockMvc mvc;
    @MockitoBean EngineService engine;
    @Test void unwrapsWriterOverloadAs503() throws Exception {
        when(engine.submit(any())).thenReturn(CompletableFuture.failedFuture(new EngineService.UnavailableException("full")));
        mvc.perform(post("/api/v1/accounts").contentType("application/json").content("{\"id\":\"busy\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("ENGINE_OVERLOADED"));
    }
}
