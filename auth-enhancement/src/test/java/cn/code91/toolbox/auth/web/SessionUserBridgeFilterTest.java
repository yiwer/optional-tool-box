package cn.code91.toolbox.auth.web;

import cn.code91.facility.web.session.SessionUserHolder;
import cn.code91.toolbox.auth.core.CurrentUser;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionUserHolder 桥接（07 §4.3，R3 opt-in）：请求内可见 CurrentUser，请求后必清
 * （finally 语义，与 facility SessionUserClearInterceptor 双清无害）。
 */
@DisplayName("SessionUserBridgeFilter 桥接")
class SessionUserBridgeFilterTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        SessionUserHolder.clear();
    }

    @Test
    void populatesDuringChainAndClearsAfter() throws ServletException, IOException {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("sub-1")
                .claim("preferred_username", "alice")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        var auth = new TestingAuthenticationToken(jwt, "n/a");
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);

        AtomicReference<CurrentUser> seenInChain = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req,
                                   jakarta.servlet.http.HttpServletResponse res) {
                seenInChain.set(SessionUserHolder.getUser(CurrentUser.class).orElse(null));
            }
        });

        new SessionUserBridgeFilter().doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(seenInChain.get()).as("链内可经 SessionUserHolder 取到 CurrentUser").isNotNull();
        assertThat(seenInChain.get().sub()).isEqualTo("sub-1");
        assertThat(SessionUserHolder.getUser(CurrentUser.class))
                .as("请求结束必清（ThreadLocal 泄漏防护）").isEmpty();
    }

    @Test
    void anonymousRequestPassesThroughWithoutPopulation() throws ServletException, IOException {
        AtomicReference<Boolean> chainRan = new AtomicReference<>(false);
        MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req,
                                   jakarta.servlet.http.HttpServletResponse res) {
                chainRan.set(true);
            }
        });
        new SessionUserBridgeFilter().doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
        assertThat(chainRan.get()).isTrue();
        assertThat(SessionUserHolder.getUser(CurrentUser.class)).isEmpty();
    }
}
