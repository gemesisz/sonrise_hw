package com.sonrise.alerting;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.sonrise.alerting.detection.DetectionScheduler;
import com.sonrise.alerting.detection.SourceRun;
import com.sonrise.alerting.domain.AppUser;
import com.sonrise.alerting.domain.Notification;
import com.sonrise.alerting.domain.NotificationStatus;
import com.sonrise.alerting.domain.Severity;
import com.sonrise.alerting.domain.UserCategory;
import com.sonrise.alerting.domain.UserChannel;
import com.sonrise.alerting.notification.NotificationRetryJob;
import com.sonrise.alerting.repository.AppUserRepository;
import com.sonrise.alerting.repository.CategoryRepository;
import com.sonrise.alerting.repository.ChannelRepository;
import com.sonrise.alerting.repository.EventRepository;
import com.sonrise.alerting.repository.NotificationRepository;
import com.sonrise.alerting.repository.UserCategoryRepository;
import com.sonrise.alerting.repository.UserChannelRepository;
import com.sonrise.alerting.source.EventCandidate;
import com.sonrise.alerting.source.FakeEventSource;
import com.sonrise.alerting.testsupport.MutableClock;
import com.sun.net.httpserver.HttpServer;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole chain with real channels: scheduler.runNow() → fake source → EventStore → commit →
 * dispatcher → EmailChannel over real SMTP (GreenMail) and SlackChannel over real HTTP (JDK
 * HttpServer) → notification rows → retry. Nothing in the application is mocked; only time is
 * controlled. Real sources are disabled so the test never touches the internet.
 */
@SpringBootTest(properties = {
        "alerting.sources.usgs.enabled=false",
        "alerting.sources.rss.enabled=false",
        "alerting.sources.coingecko.enabled=false",
        // Short timeouts so the "stalled Slack" test stays fast; proves the config reaches RestClient (D24).
        "spring.http.clients.connect-timeout=1s",
        "spring.http.clients.read-timeout=1s"
})
class EndToEndTest {

    private static final String SLACK_SECRET_PATH = "/services/T0001/B0001/SeCrEtToKeN";

    @RegisterExtension
    static GreenMailExtension smtp = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort())
            .withPerMethodLifecycle(false);

    private static HttpServer slackServer;
    /** What the fake Slack does for each incoming request, in order; empty = 200 "ok". */
    private static final Queue<SlackBehaviour> slackBehaviour = new ConcurrentLinkedQueue<>();
    private static final Queue<SlackRequest> slackRequests = new ConcurrentLinkedQueue<>();

    record SlackBehaviour(int status, Duration delay) {
    }

    record SlackRequest(String contentType, String body) {
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.now());
        }
    }

    @DynamicPropertySource
    static void mailServer(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", () -> smtp.getSmtp().getPort());
    }

    @BeforeAll
    static void startSlack() throws IOException {
        slackServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        slackServer.setExecutor(Executors.newCachedThreadPool());
        slackServer.createContext("/services/", exchange -> {
            slackRequests.add(new SlackRequest(
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            SlackBehaviour behaviour = slackBehaviour.poll();
            int status = behaviour == null ? 200 : behaviour.status();
            if (behaviour != null && !behaviour.delay().isZero()) {
                try {
                    Thread.sleep(behaviour.delay());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = (status == 200 ? "ok" : "internal_error").getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            } catch (IOException clientGaveUp) {
                // The client may have timed out and closed the connection already.
            } finally {
                exchange.close();
            }
        });
        slackServer.start();
    }

    @AfterAll
    static void stopSlack() {
        slackServer.stop(0);
    }

    @Autowired private DetectionScheduler scheduler;
    @Autowired private NotificationRetryJob retryJob;
    @Autowired private FakeEventSource fakeSource;
    @Autowired private MutableClock clock;
    @Autowired private AppUserRepository users;
    @Autowired private CategoryRepository categories;
    @Autowired private ChannelRepository channels;
    @Autowired private UserCategoryRepository subscriptions;
    @Autowired private UserChannelRepository userChannels;
    @Autowired private EventRepository events;
    @Autowired private NotificationRepository notifications;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() throws Exception {
        clock.set(Instant.now());
        smtp.purgeEmailFromAllMailboxes();
        slackBehaviour.clear();
        slackRequests.clear();
    }

    @AfterEach
    void cleanUp() {
        notifications.deleteAll();
        events.deleteAll();
        users.deleteAll();
    }

    @Test
    void newEventIsDeliveredByRealEmailAndSlack() throws Exception {
        subscribeAlice();
        injectEvent();

        List<SourceRun> runs = scheduler.runNow();

        assertThat(runs).singleElement().satisfies(run -> {
            assertThat(run.source()).isEqualTo("FAKE");
            assertThat(run.status()).isEqualTo(SourceRun.Status.COMPLETED);
            assertThat(run.result().created()).isEqualTo(1);
        });

        MimeMessage[] mails = smtp.getReceivedMessages();
        assertThat(mails).hasSize(1);
        assertThat(mails[0].getAllRecipients()[0].toString()).isEqualTo("alice@example.com");
        assertThat(mails[0].getFrom()[0].toString()).isEqualTo("alerts@alerting.local");
        assertThat(mails[0].getSubject()).isEqualTo("[HIGH] Bitcoin down 12.3% in 24h");
        assertThat(GreenMailUtil.getBody(mails[0]))
                .contains("Category: Market movements")
                .contains("Price: 76000 USD");

        assertThat(slackRequests).singleElement().satisfies(request -> {
            assertThat(request.contentType()).startsWith("application/json");
            assertThat(request.body()).contains("\"text\"").contains("*[HIGH] Bitcoin down 12.3% in 24h*");
        });

        assertThat(notificationsByChannel().values())
                .hasSize(2)
                .allSatisfy(n -> assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT));
    }

    @Test
    void slackOutageIsRetriedWhileEmailIsUnaffected() {
        subscribeAlice();
        slackBehaviour.add(new SlackBehaviour(500, Duration.ZERO));
        injectEvent();

        scheduler.runNow();

        Map<String, Notification> first = notificationsByChannel();
        assertThat(first.get("EMAIL").getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(first.get("SLACK").getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(first.get("SLACK").getLastError()).contains("500");

        clock.advance(Duration.ofMinutes(1));
        assertThat(retryJob.retryDue()).isEqualTo(1);

        Notification slack = notificationsByChannel().get("SLACK");
        assertThat(slack.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(slack.getAttempts()).isEqualTo(2);
        assertThat(slackRequests).hasSize(2);
        assertThat(smtp.getReceivedMessages()).hasSize(1); // email not sent twice
    }

    @Test
    void stalledSlackTimesOutQuicklyAndDoesNotLeakTheWebhookUrl() {
        subscribeAlice();
        slackBehaviour.add(new SlackBehaviour(200, Duration.ofSeconds(5)));
        injectEvent();

        long started = System.nanoTime();
        scheduler.runNow();
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        // read-timeout is 1s: without it, runNow() would wait for the full 5s stall.
        assertThat(took).isLessThan(Duration.ofSeconds(3));
        Notification slack = notificationsByChannel().get("SLACK");
        assertThat(slack.getStatus()).isEqualTo(NotificationStatus.FAILED);
        // The JDK HTTP client reports a read timeout as "Request cancelled", not "timed out";
        // the elapsed time above is the real proof that the timeout applied.
        assertThat(slack.getLastError()).contains("<webhook URL>");
        // The webhook URL is a secret (anyone holding it can post) and must not end up in the database.
        assertThat(slack.getLastError()).doesNotContain(SLACK_SECRET_PATH);
    }

    private void subscribeAlice() {
        AppUser alice = users.save(new AppUser("Alice"));
        subscriptions.save(new UserCategory(alice, categories.findByCode("MARKETS").orElseThrow(), Severity.MEDIUM));
        userChannels.save(new UserChannel(alice, channels.findByCode("EMAIL").orElseThrow(), "alice@example.com"));
        userChannels.save(new UserChannel(alice, channels.findByCode("SLACK").orElseThrow(),
                "http://localhost:" + slackServer.getAddress().getPort() + SLACK_SECRET_PATH));
    }

    private void injectEvent() {
        fakeSource.inject(new EventCandidate("MARKETS", "e2e-" + System.nanoTime(), "Bitcoin down 12.3% in 24h",
                "Price: 76000 USD, 24h change: -12.34%", "https://www.coingecko.com/en/coins/bitcoin",
                Severity.HIGH, clock.instant()));
    }

    private Map<String, Notification> notificationsByChannel() {
        return new TransactionTemplate(transactionManager).execute(status -> notifications.findAll().stream()
                .collect(Collectors.toMap(n -> n.getChannel().getCode(), Function.identity())));
    }
}
