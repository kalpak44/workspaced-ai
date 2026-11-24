package io.github.kalpak44.workspacedai.api.compute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.auth.Attribution;
import io.github.kalpak44.workspacedai.api.auth.Role;
import io.github.kalpak44.workspacedai.api.auth.UserEntity;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.mail.Mailer;
import io.github.kalpak44.workspacedai.api.notification.Notification;
import io.github.kalpak44.workspacedai.api.notification.NotificationService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Admins hear once that no plan can be created, and once that one can again. */
@SpringBootTest
@Import({TestDatabase.class, TestRuntime.class})
class PlanOutagesTest {

  private static final Instant START = Instant.parse("2026-11-24T08:00:00Z");

  @MockitoBean VmPricing pricing;
  @MockitoBean Mailer mailer;
  @Autowired PlanOutages outages;
  @Autowired UserRepository users;
  @Autowired NotificationService notifications;
  @Autowired PlanOutageRepository outageRows;

  private UUID admin;
  private UUID user;

  @BeforeEach
  void setUp() {
    outageRows.deleteAll();
    admin =
        users
            .save(
                new UserEntity(
                    TestUsers.subject("ops-" + UUID.randomUUID()), Role.ADMIN, Attribution.NONE))
            .getId();
    user = users.save(TestUsers.newUser("user-" + UUID.randomUUID())).getId();
  }

  @Test
  void alertsAdminsOnceAfterAWhileAndAgainWhenAPlanIsBack() {
    soldOut(true);
    outages.check(START);
    outages.check(START.plus(Duration.ofMinutes(10)));
    assertThat(titles(admin)).isEmpty();

    outages.check(START.plus(Duration.ofMinutes(15)));
    outages.check(START.plus(Duration.ofMinutes(20)));
    assertThat(titles(admin)).containsExactly("No plan can be created");
    assertThat(titles(user)).isEmpty();

    soldOut(false);
    outages.check(START.plus(Duration.ofMinutes(45)));
    assertThat(titles(admin))
        .containsExactly("Plans can be created again", "No plan can be created");
    assertThat(newest(admin).summary())
        .isEqualTo("A plan is available again after 45 minutes with none.");
    assertThat(outageRows.count()).isOne();
  }

  @Test
  void staysQuietAboutAnOutageShorterThanTheWait() {
    soldOut(true);
    outages.check(START);
    soldOut(false);
    outages.check(START.plus(Duration.ofMinutes(5)));
    assertThat(titles(admin)).isEmpty();
  }

  @Test
  void treatsAnUnreachableVmManagerAsNoNews() {
    when(pricing.sizes(false)).thenThrow(new VmManagerUnavailableException("down", null));
    outages.check(START);
    assertThat(outageRows.count()).isZero();
  }

  private void soldOut(boolean soldOut) {
    when(pricing.sizes(false))
        .thenReturn(
            List.of(
                new VmSize(
                    "Starter",
                    "cx23",
                    true,
                    2,
                    4,
                    40,
                    "shared",
                    "x86",
                    "nbg1",
                    BigDecimal.TEN,
                    BigDecimal.TEN,
                    true,
                    !soldOut)));
  }

  private List<String> titles(UUID id) {
    return notifications.list(id).stream().map(Notification::title).toList();
  }

  private Notification newest(UUID id) {
    return notifications.list(id).getFirst();
  }
}
