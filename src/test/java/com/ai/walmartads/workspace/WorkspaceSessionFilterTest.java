package com.ai.walmartads.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceSessionFilterTest {

    private WorkspaceAccountService accounts;
    private WorkspaceSessionFilter filter;

    @BeforeEach
    void setUp() {
        accounts = mock(WorkspaceAccountService.class);
        when(accounts.resolveSession(null)).thenReturn(Optional.empty());
        when(accounts.setupRequired()).thenReturn(false);
        filter = new WorkspaceSessionFilter(accounts, new ObjectMapper());
    }

    @Test
    void rejectsRemoteAccessToPinksirEmergencyResetPage() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/pinksir-reset.html");
        request.setRemoteAddr("192.168.1.20");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void allowsLoopbackAccessToPinksirEmergencyResetPage() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/pinksir-reset.html");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(request, response);
    }

    @Test
    void rejectsRemoteAccessToFirstSetupPage() throws Exception {
        when(accounts.setupRequired()).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/setup.html");
        request.setRemoteAddr("10.0.0.20");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void allowsLoopbackAccessToFirstSetupPage() throws Exception {
        when(accounts.setupRequired()).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/setup.html");
        request.setRemoteAddr("::1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(request, response);
    }
}
