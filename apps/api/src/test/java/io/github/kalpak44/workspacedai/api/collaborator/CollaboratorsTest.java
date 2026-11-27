package io.github.kalpak44.workspacedai.api.collaborator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.auth.UserEntity;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.mail.Mailer;
import io.github.kalpak44.workspacedai.api.session.WorkspaceSessionEntity;
import io.github.kalpak44.workspacedai.api.session.WorkspaceSessionRepository;
import io.github.kalpak44.workspacedai.api.workspace.Workspace;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceEntity;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestRuntime.class})
class CollaboratorsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Pattern CODE = Pattern.compile("/invitation#([A-Za-z0-9_-]+)");

  @LocalServerPort int port;

  @Autowired WorkspaceRepository workspaces;
  @Autowired WorkspaceSessionRepository sessions;
  @Autowired UserRepository users;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean Mailer mailer;

  private final HttpClient http = HttpClient.newHttpClient();

  private UserEntity owner;
  private UserEntity ana;
  private UserEntity bob;
  private UUID workspace;

  @BeforeEach
  void setUp() {
    workspaces.deleteAll();
    when(mailer.send(any())).thenReturn("1");
    owner = users.save(TestUsers.newUser("owner-" + UUID.randomUUID() + "@example.com"));
    ana = users.save(TestUsers.newUser("ana-" + UUID.randomUUID() + "@example.com"));
    bob = users.save(TestUsers.newUser("bob-" + UUID.randomUUID() + "@example.com"));
    workspace =
        workspaces
            .save(new WorkspaceEntity(owner.getId(), "Research", "openrouter", "UTC"))
            .getId();
  }

  @Test
  void invitesByMailAndKeepsNeitherTheAddressNorTheCode() throws Exception {
    var sent = send(owner, "POST", "/workspaces/" + workspace + "/invitations", invite(ana));
    assertThat(sent.statusCode()).isEqualTo(201);

    var mail = sentMail();
    assertThat(mail.to()).extracting(Mailer.Address::email).containsExactly(email(ana));
    assertThat(mail.text()).contains("Research", "24 hours", "ignore this email");
    var code = code(mail);
    var stored =
        jdbc.queryForList(
            "select email_hash || ' ' || code_hash from workspace_invitation", String.class);
    assertThat(stored).singleElement().asString().doesNotContain(email(ana), code);

    assertThat(list(owner))
        .extracting(Collaborator::status, Collaborator::email)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(CollaboratorStatus.OWNER, email(owner)),
            org.assertj.core.groups.Tuple.tuple(CollaboratorStatus.PENDING, null));
  }

  @Test
  void acceptingOnceJoinsTheWorkspaceForTheInvitedAddressOnly() throws Exception {
    send(owner, "POST", "/workspaces/" + workspace + "/invitations", invite(ana));
    var code = code(sentMail());

    // Someone else holding the link cannot use it, and it still works for the right address.
    assertThat(accept(bob, code).statusCode()).isEqualTo(403);
    assertThat(send(bob, "GET", "/workspaces/" + workspace + "/collaborators", null).statusCode())
        .isEqualTo(404);

    var accepted = accept(ana, code);
    assertThat(accepted.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(accepted.body()).get("workspaceId").asString())
        .isEqualTo(workspace.toString());
    assertThat(accept(ana, code).statusCode()).isEqualTo(404);

    var shared = workspaces(ana);
    assertThat(shared).extracting(Workspace::id).containsExactly(workspace);
    assertThat(shared.getFirst().ownerId()).isEqualTo(owner.getId());
    assertThat(list(ana))
        .extracting(Collaborator::status, Collaborator::email, Collaborator::invitedBy)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(CollaboratorStatus.OWNER, email(owner), null),
            org.assertj.core.groups.Tuple.tuple(
                CollaboratorStatus.APPLIED, email(ana), email(owner)));
  }

  @Test
  void anExpiredInvitationOpensNothing() throws Exception {
    send(owner, "POST", "/workspaces/" + workspace + "/invitations", invite(ana));
    var code = code(sentMail());
    jdbc.update("update workspace_invitation set expires_at = now() - interval '1 minute'");

    assertThat(accept(ana, code).statusCode()).isEqualTo(404);
    assertThat(list(owner))
        .extracting(Collaborator::status)
        .containsExactly(CollaboratorStatus.OWNER);
  }

  @Test
  void aCollaboratorCanDoAllButEndThePaidWorkspaceOrRemoveOthers() throws Exception {
    join(ana);
    join(bob);

    // Collaborators invite too.
    assertThat(
            send(
                    ana,
                    "POST",
                    "/workspaces/" + workspace + "/invitations",
                    "{\"email\":\"carol@example.com\"}")
                .statusCode())
        .isEqualTo(201);
    assertThat(send(ana, "PUT", "/workspaces/" + workspace, "{\"name\":\"Shared\"}").statusCode())
        .isEqualTo(200);
    assertThat(send(ana, "DELETE", "/workspaces/" + workspace, null).statusCode()).isEqualTo(403);
    assertThat(
            send(ana, "DELETE", "/workspaces/" + workspace + "/collaborators/" + bob.getId(), null)
                .statusCode())
        .isEqualTo(403);
    var pending = list(ana).getLast();
    assertThat(
            send(
                    ana,
                    "DELETE",
                    "/workspaces/" + workspace + "/invitations/" + pending.invitationId(),
                    null)
                .statusCode())
        .isEqualTo(403);

    assertThat(
            send(
                    owner,
                    "DELETE",
                    "/workspaces/" + workspace + "/collaborators/" + bob.getId(),
                    null)
                .statusCode())
        .isEqualTo(204);
    assertThat(send(bob, "GET", "/workspaces/" + workspace + "/collaborators", null).statusCode())
        .isEqualTo(404);
    assertThat(workspaces(bob)).isEmpty();

    // Leaving is removing oneself.
    assertThat(
            send(ana, "DELETE", "/workspaces/" + workspace + "/collaborators/" + ana.getId(), null)
                .statusCode())
        .isEqualTo(204);
    assertThat(workspaces(ana)).isEmpty();
  }

  @Test
  void eachUserSeesOnlyTheSessionsTheyStartedAndEveryTaskRun() throws Exception {
    join(ana);
    var mine = session(owner.getId(), null);
    var hers = session(ana.getId(), null);
    var run = session(null, "nightly");

    assertThat(sessionIds(owner)).containsExactly(mine, run);
    assertThat(sessionIds(ana)).containsExactly(hers, run);
    assertThat(
            send(ana, "GET", "/workspaces/" + workspace + "/sessions/" + mine + "/messages", null)
                .statusCode())
        .isEqualTo(404);
    assertThat(
            send(ana, "DELETE", "/workspaces/" + workspace + "/sessions/" + mine, null)
                .statusCode())
        .isEqualTo(404);
  }

  @Test
  void aDemoCannotBeShared() throws Exception {
    var demo =
        workspaces
            .save(new WorkspaceEntity(owner.getId(), "Trial", "openrouter", "UTC", true))
            .getId();

    var refused = send(owner, "POST", "/workspaces/" + demo + "/invitations", invite(ana));

    assertThat(refused.statusCode()).isEqualTo(409);
    assertThat(JSON.readTree(refused.body()).get("detail").asString())
        .isEqualTo("a demo workspace cannot be shared");
    verify(mailer, never()).send(any());
  }

  private void join(UserEntity user) throws Exception {
    clearInvocations(mailer);
    send(owner, "POST", "/workspaces/" + workspace + "/invitations", invite(user));
    assertThat(accept(user, code(sentMail())).statusCode()).isEqualTo(200);
  }

  private UUID session(UUID user, String task) {
    var id = UUID.randomUUID();
    sessions.save(new WorkspaceSessionEntity(id, workspace, user, "/p/" + id, "ses_" + id, task));
    return id;
  }

  private List<UUID> sessionIds(UserEntity user) throws Exception {
    var response = send(user, "GET", "/workspaces/" + workspace + "/sessions", null);
    assertThat(response.statusCode()).isEqualTo(200);
    return JSON.readTree(response.body())
        .valueStream()
        .map(s -> UUID.fromString(s.get("id").asString()))
        .toList();
  }

  private Mailer.Mail sentMail() {
    var mail = ArgumentCaptor.forClass(Mailer.Mail.class);
    verify(mailer).send(mail.capture());
    return mail.getValue();
  }

  private static String code(Mailer.Mail mail) {
    var match = CODE.matcher(mail.text());
    assertThat(match.find()).isTrue();
    return match.group(1);
  }

  private HttpResponse<String> accept(UserEntity user, String code) throws Exception {
    return send(user, "POST", "/invitations/accept", "{\"code\":\"" + code + "\"}");
  }

  private List<Collaborator> list(UserEntity user) throws Exception {
    var response = send(user, "GET", "/workspaces/" + workspace + "/collaborators", null);
    assertThat(response.statusCode()).isEqualTo(200);
    return JSON.readValue(response.body(), new TypeReference<>() {});
  }

  private List<Workspace> workspaces(UserEntity user) throws Exception {
    return JSON.readValue(send(user, "GET", "/workspaces", null).body(), new TypeReference<>() {});
  }

  private static String invite(UserEntity user) {
    return "{\"email\":\"" + email(user).toUpperCase() + "\"}";
  }

  private static String email(UserEntity user) {
    return user.getIdpSubject().substring("test|".length());
  }

  private HttpResponse<String> send(UserEntity user, String method, String path, String json)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
            .header("Authorization", TestUsers.bearer(user))
            .header("Content-Type", "application/json")
            .method(
                method,
                json == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json))
            .build();
    return http.send(request, BodyHandlers.ofString());
  }
}
