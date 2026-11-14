package io.github.kalpak44.workspacedai.vmmanager.vm;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kalpak44.workspacedai.vmmanager.hetzner.FakeHetznerClient;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.HetznerException;
import io.github.kalpak44.workspacedai.vmmanager.hetzner.PlacementGroup;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class UserPlacementGroupsTest {

  final FakeHetznerClient hetzner = new FakeHetznerClient();
  final UserPlacementGroups groups = new UserPlacementGroups(hetzner);
  final UUID user = UUID.randomUUID();

  @Test
  void createsAUsersGroupOnceAndReusesIt() {
    var first = groups.groupFor(user);
    var second = groups.groupFor(user);

    assertThat(first).isNotNull().isEqualTo(second);
    var group = hetzner.groups.get(first);
    assertThat(group.name()).isEqualTo("user-" + user);
    assertThat(group.labels())
        .containsEntry("managed-by", "workspaced-ai-vm-manager")
        .containsEntry("user-id", user.toString());
  }

  @Test
  void givesEachUserTheirOwnGroup() {
    assertThat(groups.groupFor(user)).isNotEqualTo(groups.groupFor(UUID.randomUUID()));
  }

  @Test
  void offersNoGroupOnceTheUsersIsFull() {
    var id = groups.groupFor(user);
    var full = LongStream.range(0, PlacementGroup.SPREAD_LIMIT).boxed().toList();
    hetzner.groups.computeIfPresent(
        id, (_, g) -> new PlacementGroup(g.id(), g.name(), full, g.labels()));

    assertThat(groups.groupFor(user)).isNull();
  }

  @Test
  void deletesAGroupOnlyOnceItsLastServerIsGone() {
    var id = groups.groupFor(user);
    hetzner.groups.computeIfPresent(
        id, (_, g) -> new PlacementGroup(g.id(), g.name(), List.of(1L, 2L), g.labels()));

    groups.releaseIfEmpty(user, 1L);
    assertThat(hetzner.groups).containsKey(id);

    hetzner.groups.computeIfPresent(
        id, (_, g) -> new PlacementGroup(g.id(), g.name(), List.of(2L), g.labels()));
    groups.releaseIfEmpty(user, 2L);
    assertThat(hetzner.groups).doesNotContainKey(id);
  }

  @Test
  void leavesTheGroupWhenHetznerRefusesToDeleteIt() {
    groups.groupFor(user);
    hetzner.failure = new HetznerException("Hetzner answered 422 on DELETE /placement_groups/1");

    groups.releaseIfEmpty(user, 1L);

    hetzner.failure = null;
    assertThat(hetzner.placementGroups("user-id=" + user)).hasSize(1);
  }

  @Test
  void neverTouchesAnotherUsersGroup() {
    var other = UUID.randomUUID();
    groups.groupFor(other);

    groups.releaseIfEmpty(user, 1L);

    assertThat(hetzner.placementGroups("user-id=" + other)).hasSize(1);
    assertThat(hetzner.deletedGroups).isEmpty();
    assertThat(hetzner.groups.values())
        .extracting(PlacementGroup::labels)
        .containsExactly(
            Map.of("managed-by", "workspaced-ai-vm-manager", "user-id", other.toString()));
  }
}
