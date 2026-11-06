package io.github.kalpak44.workspacedai.api.user;

import io.github.kalpak44.workspacedai.api.auth.UserEntity;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceService;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Everyone who can sign in: listed a page at a time, created, and deleted with their workspaces. */
@Service
public class UserService {

  /** The fields a page may be sorted by; anything else is refused rather than guessed at. */
  static final Set<String> SORTABLE = Set.of("username", "firstName", "lastName", "createdAt");

  private final UserRepository users;
  private final WorkspaceRepository workspaceRows;
  private final WorkspaceService workspaces;
  private final PasswordEncoder passwords;
  private final TransactionTemplate transactions;

  public UserService(
      UserRepository users,
      WorkspaceRepository workspaceRows,
      WorkspaceService workspaces,
      PasswordEncoder passwords,
      TransactionTemplate transactions) {
    this.users = users;
    this.workspaceRows = workspaceRows;
    this.workspaces = workspaces;
    this.passwords = passwords;
    this.transactions = transactions;
  }

  /** The id breaks ties, so a user never shows on two pages or on none. */
  @Transactional(readOnly = true)
  public Page<User> list(Pageable pageable) {
    var unknown =
        pageable.getSort().stream()
            .map(Sort.Order::getProperty)
            .filter(p -> !SORTABLE.contains(p))
            .toList();
    if (!unknown.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "cannot sort by " + String.join(", ", unknown));
    }
    var sort = pageable.getSort().and(Sort.by("id"));
    var page = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    return users.findAll(page).map(User::of);
  }

  /** Conflict when the username is taken, by an earlier user or one created at the same time. */
  public User create(String username, String password, String firstName, String lastName) {
    var hash = passwords.encode(password);
    try {
      return transactions.execute(
          _ -> {
            if (users.existsByUsername(username)) {
              throw taken(username);
            }
            return User.of(
                users.saveAndFlush(new UserEntity(username, hash, firstName, lastName)));
          });
    } catch (DataIntegrityViolationException e) {
      throw taken(username);
    }
  }

  /**
   * Deletes each of the user's workspaces the way the workspace route does, so their pods are
   * stopped rather than orphaned, then the user. Workspace deletes wait for their pods, so no
   * transaction is held across them. False when no user has that id.
   */
  public boolean delete(UUID id, UUID requestedBy) {
    if (id.equals(requestedBy)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "you cannot delete yourself");
    }
    if (!users.existsById(id)) {
      return false;
    }
    workspaceRows.findIdsByOwnerId(id).forEach(workspaces::delete);
    transactions.executeWithoutResult(_ -> users.deleteById(id));
    return true;
  }

  private static ResponseStatusException taken(String username) {
    return new ResponseStatusException(HttpStatus.CONFLICT, "username " + username + " is taken");
  }
}
