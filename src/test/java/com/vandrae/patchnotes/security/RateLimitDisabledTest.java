package com.vandrae.patchnotes.security;

import com.vandrae.patchnotes.externalapi.SteamNewsClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/** The off switch really is off: a limit of one request still lets everything through. */
@SpringBootTest(properties = {
        "app.security.rate-limit.enabled=false",
        "app.security.rate-limit.login.burst=1", "app.security.rate-limit.login.per-minute=1"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class RateLimitDisabledTest {

    @Autowired MockMvc mvc;
    @MockitoBean SteamNewsClient news;

    @Test
    void nothingIsLimitedWhenRateLimitingIsDisabled() throws Exception {
        for (int i = 0; i < 25; i++) {
            mvc.perform(get(SteamLoginController.LOGIN_PATH))
                    .andExpect(header().string(HttpHeaders.LOCATION, containsString("steamcommunity.com/openid/login")));
        }
    }
}
