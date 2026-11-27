package io.github.kalpak44.workspacedai.api.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.billing.BillingService;
import io.github.kalpak44.workspacedai.api.compute.ProvisionedVm;
import io.github.kalpak44.workspacedai.api.compute.VmManager;
import io.github.kalpak44.workspacedai.api.compute.VmSize;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** What a create that timed out or died halfway leaves behind is deleted, charged or ended. */
@SpringBootTest
@Import({TestDatabase.class, TestRuntime.class})
class VmReconcilerTest {

  private static final VmSize CX23 =
      new VmSize(
          null,
          "cx23",
          true,
          2,
          4.0,
          40,
          "shared",
          "x86",
          "nbg1",
          new BigDecimal("5.03"),
          new BigDecimal("5.99"),
          true,
          false,
          true);

  @MockitoBean VmManager vmManager;
  @Autowired VmReconciler reconciler;
  @Autowired WorkspaceRepository workspaces;
  @Autowired UserRepository users;
  @Autowired BillingService billing;

  private UUID user;

  @BeforeEach
  void prepare() {
    when(vmManager.sizes()).thenReturn(List.of(CX23));
    user = users.save(TestUsers.newUser("owner-" + UUID.randomUUID() + "@example.com")).getId();
  }

  @Test
  void deletesTheVmOfAWorkspaceThatIsGoneOrBackToADraftAndNoOther() {
    var gone = UUID.randomUUID();
    var draft =
        workspaces.save(WorkspaceEntity.draft(user, "Draft", "openrouter", "cx23", "UTC", false));
    var live = launched(false);
    when(vmManager.vms())
        .thenReturn(List.of(vm(gone, "READY"), vm(draft.getId(), "READY"), vm(live, "READY")));

    reconciler.run();

    verify(vmManager).deleteFor(gone);
    verify(vmManager).deleteFor(draft.getId());
    verify(vmManager, never()).deleteFor(live);
  }

  @Test
  void deletesNothingWhenTooManyVmsLookOrphanedAtOnce() {
    when(vmManager.vms())
        .thenReturn(
            List.of(
                vm(UUID.randomUUID(), "READY"),
                vm(UUID.randomUUID(), "READY"),
                vm(UUID.randomUUID(), "READY"),
                vm(UUID.randomUUID(), "READY")));

    reconciler.run();

    verify(vmManager, never()).deleteFor(any());
  }

  @Test
  void chargesARunningWorkspaceItsCreateNeverStartedCharging() {
    var running = launched(false);
    var booting = launched(false);
    when(vmManager.vms()).thenReturn(List.of(vm(running, "READY"), vm(booting, "PROVISIONING")));

    reconciler.run();

    assertThat(billing.isCharged(running)).isTrue();
    // Still being created, so its create may yet charge it.
    assertThat(billing.isCharged(booting)).isFalse();
    assertThat(billing.startChargingIfUncharged(running, user, BigDecimal.TEN)).isFalse();
  }

  @Test
  void endsARunningDemoItsCreateNeverGaveAnEnd() {
    var demo = launched(true);
    when(vmManager.vms()).thenReturn(List.of(vm(demo, "READY")));

    reconciler.run();

    var expiresAt = workspaces.findById(demo).orElseThrow().getExpiresAt();
    assertThat(expiresAt).isAfter(Instant.now());
    assertThat(billing.isCharged(demo)).isFalse();
  }

  private UUID launched(boolean demo) {
    return workspaces
        .save(new WorkspaceEntity(user, "Workspace", "openrouter", "UTC", demo))
        .getId();
  }

  private static ProvisionedVm vm(UUID workspaceId, String status) {
    return new ProvisionedVm(UUID.randomUUID(), workspaceId, "cx23", status);
  }
}
