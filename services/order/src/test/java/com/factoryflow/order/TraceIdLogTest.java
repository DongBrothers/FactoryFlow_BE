package com.factoryflow.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.factoryflow.common.web.TraceIdFilter;
import com.factoryflow.order.client.production.ProductClient;
import com.factoryflow.test.container.TestContainersConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 로그 줄마다 traceId 가 찍힌다 (logging.pattern.correlation). 요청의 X-Trace-Id 가 TraceIdFilter 를 거쳐 MDC 에
 * 들어가고, 그 요청을 처리하는 동안 남긴 로그에 [traceId] 로 보인다 (#67, docs/specs/saga-order-fulfillment.md "추적").
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class TraceIdLogTest {

    @Autowired WebApplicationContext context;
    @MockitoBean ProductClient productClient;

    @Test
    void requestLogLineCarriesTraceId(CapturedOutput output) throws Exception {
        MockMvc mockMvc =
                MockMvcBuilders.webAppContextSetup(context).addFilter(new TraceIdFilter()).build();

        mockMvc.perform(
                        post("/orders")
                                .header(TraceIdFilter.HEADER, "trace-log-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"items\":[{\"productId\":1,\"qty\":1}]}"))
                .andExpect(status().isBadRequest());

        assertThat(output.getOut().lines().filter(line -> line.contains("필수 헤더 누락")))
                .singleElement()
                .asString()
                .contains("[trace-log-1]");
    }
}
