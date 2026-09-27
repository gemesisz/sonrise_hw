package com.sonrise.alerting.admin;

import com.sonrise.alerting.channel.EmailChannel;
import com.sonrise.alerting.repository.AppUserRepository;
import com.sonrise.alerting.repository.ChannelRepository;
import com.sonrise.alerting.repository.EventRepository;
import com.sonrise.alerting.repository.NotificationRepository;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

/**
 * Shared setup for admin API tests: the real application (one cached context), real security,
 * real sources disabled so nothing touches the internet. EmailChannel is a spy: real behaviour
 * unless a test stubs it.
 */
@SpringBootTest(properties = {
        "alerting.sources.usgs.enabled=false",
        "alerting.sources.rss.enabled=false",
        "alerting.sources.coingecko.enabled=false"
})
@AutoConfigureMockMvc
abstract class AdminApiTestBase {

    @Autowired protected MockMvc mvc;
    @Autowired protected AppUserRepository users;
    @Autowired protected ChannelRepository channels;
    @Autowired protected EventRepository events;
    @Autowired protected NotificationRepository notifications;

    @MockitoSpyBean
    protected EmailChannel emailChannel;

    @AfterEach
    void cleanDatabase() {
        notifications.deleteAll();
        events.deleteAll();
        users.deleteAll();
        channels.findAll().forEach(channel -> {
            channel.setEnabled(true);
            channels.save(channel);
        });
    }

    /** Authenticated as the admin, with a CSRF token (needed for anything but GET). */
    protected static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.with(httpBasic("admin", "test-password")).with(csrf());
    }

    protected static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return asAdmin(request).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
