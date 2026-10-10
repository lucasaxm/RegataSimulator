package com.boatarde.regatasimulator.configuration;

import com.boatarde.regatasimulator.controller.CsrfController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@WebMvcTest(controllers=CsrfController.class,properties="regata-simulator.web.login.max-attempts=3")
@ContextConfiguration(classes={CsrfController.class,SecurityConfig.class,CorsConfig.class,SessionConfig.class})
@ActiveProfiles("test")
class LoginThrottleTest {
    @Autowired MockMvc mvc;
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login(String ip,String user,String password) {
        return post("/api/login").with(csrf()).with(r -> { r.setRemoteAddr(ip); return r; })
            .header("X-Requested-With","XMLHttpRequest").param("username",user).param("password",password);
    }
    @Test void fullFilterChainRejectsRepeatedAttemptsAndSpoofedForwardedAddresses() throws Exception {
        for(int i=0;i<3;i++) mvc.perform(login("198.51.100.1","unknown-fixture-user","wrong").header("X-Forwarded-For","203.0.113."+i))
            .andExpect(status().isUnauthorized());
        mvc.perform(login("198.51.100.1","another-user","wrong").header("X-Forwarded-For","192.0.2.99"))
            .andExpect(status().isTooManyRequests()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("detail").value("Não foi possível autenticar agora."));
    }
    @Test void realSuccessfulAuthenticationResetsBothBuckets() throws Exception {
        for(int i=0;i<2;i++) mvc.perform(login("198.51.100.2","phase0-admin","wrong")).andExpect(status().isUnauthorized());
        mvc.perform(login("198.51.100.2","phase0-admin","phase0-test-password")).andExpect(status().isNoContent());
        mvc.perform(login("198.51.100.2","phase0-admin","wrong")).andExpect(status().isUnauthorized());
    }
    @Test void ttlCapacityAndInvalidBoundsFailClosedWithoutUnboundedGrowth() {
        var clock=new MutableClock(); var limiter=new LoginAttemptLimiter(clock,2,1000,10);
        assertTrue(limiter.reserve("ip","u")); assertTrue(limiter.reserve("ip","u")); assertFalse(limiter.reserve("ip","u"));
        clock.millis=1001; assertTrue(limiter.reserve("ip","u"));
        for(int i=0;i<1000;i++) limiter.reserve("ip"+i,"u"+i);
        assertTrue(limiter.size()<=10); assertFalse(limiter.reserve("new-ip","new-user"));
        clock.millis=2002; assertTrue(limiter.reserve("new-ip","new-user"));
        assertThrows(IllegalArgumentException.class,()->new LoginAttemptLimiter(clock,0,1000,10));
    }
    private static final class MutableClock extends Clock {
        long millis;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
    }

    @Test void concurrentAttemptsCannotExceedReservationLimit() throws Exception {
        var limiter=new LoginAttemptLimiter(Clock.systemUTC(),3,60000,10);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var tasks=new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for(int i=0;i<50;i++) tasks.add(pool.submit(()->limiter.reserve("synthetic-ip","synthetic-user")));
            int allowed=0; for(var result:tasks) if(result.get()) allowed++;
            assertEquals(3,allowed); assertEquals(2,limiter.size());
        }
    }
}